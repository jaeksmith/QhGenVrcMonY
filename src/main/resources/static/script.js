document.addEventListener('DOMContentLoaded', () => {
    // We'll fetch the actual build timestamp from the server
    let buildTimestamp = "Unknown"; // Default value
    
    // Function to fetch build info from server
    const fetchBuildInfo = async () => {
        try {
            const response = await fetch('/api/system/build-info');
            if (response.ok) {
                const buildInfo = await response.json();
                buildTimestamp = buildInfo.buildTime || "Unknown";
                log('info', `Received build timestamp from server: ${buildTimestamp}`);
                
                // Update the admin panel with the real build timestamp
                updateVersionDisplay();
            } else {
                log('warn', `Failed to fetch build info: ${response.status}`);
            }
        } catch (error) {
            log('error', `Error fetching build info: ${error.message}`);
        }
    };
    
    // Try to fetch build info
    fetchBuildInfo();
    
    // TEST: Add console message to verify script update
    console.log(`SCRIPT UPDATE TEST: Build time will be fetched from server`);
    
    // Add version info to the admin panel instead of a floating marker
    const updateVersionDisplay = () => {
        const adminPanel = document.getElementById('admin-ops');
        if (adminPanel) {
            // Remove any existing version info
            const existingInfo = adminPanel.querySelector('.version-info');
            if (existingInfo) {
                existingInfo.parentNode.removeChild(existingInfo);
            }
            
            const versionInfo = document.createElement('div');
            versionInfo.className = 'version-info';
            versionInfo.innerHTML = `<p>UI Build: ${buildTimestamp}</p>`;
            versionInfo.style.marginTop = '20px';
            versionInfo.style.padding = '5px';
            versionInfo.style.backgroundColor = '#444';
            versionInfo.style.borderRadius = '4px';
            versionInfo.style.fontSize = '0.9em';
            
            // Find admin-controls div and append after it
            const adminControls = adminPanel.querySelector('.admin-controls');
            if (adminControls) {
                adminControls.appendChild(versionInfo);
            } else {
                adminPanel.appendChild(versionInfo);
            }
        }
    };
    
    // Call this after a short delay to ensure DOM is ready
    setTimeout(updateVersionDisplay, 500);
    
    const wsUri = `ws://${window.location.host}/ws/status`;
    let websocket;
    let userData = new Map(); // Store user data by vrcUid { latestState: DTO, previousState: DTO }
    let userOrder = []; // Maintain order from config
    let connectionStatus = 'closed'; // 'open', 'closed', 'error'
    let backendApiStatus = 'unknown'; // 'ok', 'error', 'unknown'
    let lastUpdateTime = null;
    let statusMessage = 'Initializing...';
    let timeScale = 1; // Default seconds per pixel
    
    // Session status variables
    let hasActiveSession = false;
    let lastSessionTime = null;
    let disconnectedOverlay = null;
    let disconnectedTimer = null;
    let loginDialogVisible = false;
    let twoFactorRequired = false;

    // Tab handling variables
    const tabButtons = document.querySelectorAll('.tab-button');
    const tabPanes = document.querySelectorAll('.tab-pane');
    const logConsoleOutput = document.querySelector('.log-console-output');
    
    // Log filter checkboxes
    const clientRequestsCheckbox = document.querySelector('input[type="checkbox"][data-filter="client-requests"]');
    const clientResponsesCheckbox = document.querySelector('input[type="checkbox"][data-filter="client-responses"]');
    const serverRequestsCheckbox = document.querySelector('input[type="checkbox"][data-filter="server-requests"]');
    const serverResponsesCheckbox = document.querySelector('input[type="checkbox"][data-filter="server-responses"]');
    const clientLoggingCheckbox = document.querySelector('input[type="checkbox"][data-filter="client-logging"]');
    
    // Log storage - keeps logs in memory until they're displayed
    const logEntries = {
        clientRequests: [],
        clientResponses: [],
        serverRequests: [],
        serverResponses: [],
        clientLogging: []
    };
    
    // Maximum number of logs to store per category
    const MAX_LOGS_PER_CATEGORY = 100;
    
    // For uptime simulation
    let serverStartTime = null;
    let startTime = new Date(); // Client start time (fallback)
    let uptimeInterval;

    // Variables for connection management
    let isServerShuttingDown = false;
    let reconnectAttempts = 0;
    let maxReconnectDelay = 5 * 60 * 1000; // 5 minutes in milliseconds
    let reconnectIncrement = 5 * 1000; // 5 seconds in milliseconds (changed from 15)
    let reconnectTimer = null;
    let disconnectionMessage = null;

    // --- DOM Elements ---
    const websocketStatusDot = document.getElementById('websocket-status');
    const backendStatusDot = document.getElementById('backend-status');
    const lastUpdateTimeSpan = document.getElementById('last-update-time');
    const statusMessageSpan = document.getElementById('status-message');
    const quickStatusBar = document.getElementById('quick-status-bar');
    const timelineUsers = document.getElementById('timeline-users');
    const refreshButton = document.getElementById('refresh-button');
    const timeScaleSelect = document.getElementById('time-scale-select');
    
    // --- Section Toggle Elements ---
    const statusLineSection = document.getElementById('status-line');
    const statusSectionToggle = document.getElementById('status-section-toggle');
    const quickStatusSection = document.getElementById('quick-status-bar');
    const quickStatusSectionToggle = document.getElementById('quick-status-section-toggle');
    const timelineContainer = document.getElementById('timeline-container');
    const timelineSectionToggle = document.getElementById('timeline-section-toggle');

    // --- Status Color Mapping ---
    const statusColors = {
        ONLINE: 'var(--status-online)',
        OFFLINE: 'var(--status-offline)',
        ON_WEBSITE: 'var(--status-on-website)', // New state
        OTHER: 'var(--status-other)',
        ERROR: 'var(--status-error)'
    };
    const statusClasses = {
        ONLINE: 'status-bg-online',
        OFFLINE: 'status-bg-offline',
        ON_WEBSITE: 'status-bg-on-website', // New state
        OTHER: 'status-bg-other',
        ERROR: 'status-bg-error'
    };

    // Define status priority order (highest to lowest)
    const statusPriority = {
        'ONLINE': 0,
        'ON_WEBSITE': 1,
        'ERROR': 2,
        'OTHER': 3,  // UNKNOWN states (orange)
        'OFFLINE': 4
    };

    // --- TTS Setup ---
    const synth = window.speechSynthesis;
    let voices = [];
    function populateVoiceList() {
      if(typeof synth === 'undefined') return;
      voices = synth.getVoices();
      // You could add logic here to select a preferred voice if desired
    }
    populateVoiceList();
    if (typeof synth !== 'undefined' && synth.onvoiceschanged !== undefined) {
      synth.onvoiceschanged = populateVoiceList;
    }

    // --- Utility Functions ---
    // Original console methods to preserve
    const originalConsole = {
        log: console.log,
        warn: console.warn,
        error: console.error,
        debug: console.debug,
        info: console.info
    };

    // Override console methods to capture logs
    function setupConsoleCapture() {
        console.log = function() {
            originalConsole.log.apply(console, arguments);
            captureConsoleLog('log', arguments);
        };
        
        console.warn = function() {
            originalConsole.warn.apply(console, arguments);
            captureConsoleLog('warn', arguments);
        };
        
        console.error = function() {
            originalConsole.error.apply(console, arguments);
            captureConsoleLog('error', arguments);
        };
        
        console.debug = function() {
            originalConsole.debug.apply(console, arguments);
            captureConsoleLog('debug', arguments);
        };
        
        console.info = function() {
            originalConsole.info.apply(console, arguments);
            captureConsoleLog('info', arguments);
        };
    }

    // Capture console logs
    function captureConsoleLog(level, args) {
        // Convert arguments to array and join them
        const argsArray = Array.from(args);
        const message = argsArray.map(arg => {
            if (typeof arg === 'object') {
                try {
                    return JSON.stringify(arg);
                } catch(e) {
                    return String(arg);
                }
            }
            return String(arg);
        }).join(' ');
        
        // Create a timestamp for this log entry
        const timestamp = new Date();
        
        // Add to client logging array directly
        const entry = {
            timestamp: timestamp,
            content: message,
            level: level
        };
        logEntries.clientLogging.push(entry);
        
        // Trim if exceeds max size
        if (logEntries.clientLogging.length > MAX_LOGS_PER_CATEGORY) {
            logEntries.clientLogging.shift();
        }
        
        // Update log console if it's visible and the filter is enabled
        if (clientLoggingCheckbox && clientLoggingCheckbox.checked) {
            updateLogConsole();
        }
    }

    function log(level, message) {
        const now = new Date();
        
        // Use the original console methods to avoid capturing our own logs
        switch(level) {
            case 'debug': originalConsole.debug(`[${now.toLocaleTimeString()}] ${message}`); break;
            case 'info': originalConsole.info(`[${now.toLocaleTimeString()}] ${message}`); break;
            case 'warn': originalConsole.warn(`[${now.toLocaleTimeString()}] ${message}`); break;
            case 'error': originalConsole.error(`[${now.toLocaleTimeString()}] ${message}`); break;
            default: originalConsole.log(`[${now.toLocaleTimeString()}] ${message}`);
        }
    }

    function formatTimestamp(isoString) {
        if (!isoString) return '--:--:--';
        try {
            return new Date(isoString).toLocaleTimeString();
        } catch (e) {
            return '--:--:--';
        }
    }

    function determineDisplayStatus(latestStateDTO) { 
        if (!latestStateDTO) {
            log('error', 'determineDisplayStatus called with null state data');
            return 'ERROR';
        }
        
        // Handle server-reported error state
        if (latestStateDTO.statusType === 'ERROR') {
            log('debug', `User ${latestStateDTO.hrToken} has ERROR status: ${latestStateDTO.errorMessage || 'No error message'}`);
            return 'ERROR';
        }
        
        // Handle server-reported unknown state (when we have session but no user data yet)
        if (latestStateDTO.statusType === 'UNKNOWN') {
            log('debug', `User ${latestStateDTO.hrToken} has UNKNOWN status: ${latestStateDTO.errorMessage || 'Initializing...'}`);
            return 'OTHER'; // Map UNKNOWN to OTHER (orange) for now
        }
        
        // Handle null user data (could happen during initialization or if API returns incomplete data)
        if (!latestStateDTO.user) {
            log('debug', `User ${latestStateDTO.hrToken} has null user data`);
            return 'OTHER';
        }
        
        // Handle null state within user object
        if (!latestStateDTO.user.state) {
            log('debug', `User ${latestStateDTO.hrToken} has null state property`);
            return 'OTHER';
        }
        
        const state = latestStateDTO.user.state.toLowerCase(); // Ensure lowercase for comparison
        const status = latestStateDTO.user.status ? latestStateDTO.user.status.toLowerCase() : null;
        const location = latestStateDTO.user.location ? latestStateDTO.user.location.toLowerCase() : null;
        
        log('debug', `User ${latestStateDTO.hrToken} state="${state}", status="${status}", location="${location}"`);
        
        // Apply status determination logic
        if (state === 'online') {
            return 'ONLINE';
        } else if (state === 'active') {
            // Check location to differentiate online vs website
            return location && location !== 'offline' ? 'ONLINE' : 'ON_WEBSITE';
        } else if (state === 'offline') {
            return 'OFFLINE';
        } else {
            log('warn', `Unknown state value for user ${latestStateDTO.hrToken}: "${state}"`);
            return 'OTHER'; // Any other state reported by API
        }
    }
    
    function getUserIconUrl(user) {
        const defaultIcon = 'https://assets.vrchat.com/system/defaultAvatarThumbnail.png';
        if (!user) return defaultIcon;
        if (user.userIcon && user.userIcon.trim() !== '') return user.userIcon;
        if (user.currentAvatarThumbnailImageUrl && user.currentAvatarThumbnailImageUrl.trim() !== '') return user.currentAvatarThumbnailImageUrl;
        return defaultIcon;
    }

    function announceStatusChange(previousStateDTO, newStateDTO) {
        if (!previousStateDTO || !newStateDTO || typeof synth === 'undefined') return; // Need both states and synth support
        
        const oldStatus = determineDisplayStatus(previousStateDTO);
        const newStatus = determineDisplayStatus(newStateDTO);

        // Only announce actual state changes (ONLINE, OFFLINE, OTHER)
        if (oldStatus === newStatus) return; 

        // Determine base volume
        let baseVolume = 0.3; // Default for 'other'
        if (newStatus === 'ONLINE') {
            baseVolume = 1.0;
        } else if (newStatus === 'OFFLINE') {
            baseVolume = 0.5;
        }

        // Apply multiplier
        const volumeMultiplier = newStateDTO.announceVolumeMult != null ? newStateDTO.announceVolumeMult : 1.0;
        let finalVolume = baseVolume * volumeMultiplier;
        // Clamp volume between 0 and 1
        finalVolume = Math.max(0, Math.min(1, finalVolume));

        // Get name (handle slash)
        let nameToAnnounce = newStateDTO.hrToken || 'User';
        if (nameToAnnounce.includes('/')) {
            nameToAnnounce = nameToAnnounce.split('/')[0];
        }

        // Get state string
        let stateString = newStatus.toLowerCase(); 
        if (stateString === 'active') stateString = 'active'; // Use 'active' if mapped to that
        else if (stateString === 'other') stateString = 'in an unknown state';

        // Construct utterance
        const utteranceText = `${nameToAnnounce} is now ${stateString}.`;
        log('info', `Announcing: "${utteranceText}" at volume ${finalVolume}`);
        
        const utterance = new SpeechSynthesisUtterance(utteranceText);
        utterance.volume = finalVolume;
        // Optional: Select a specific voice if desired
        // const selectedVoice = voices.find(voice => voice.name === 'Your Preferred Voice Name');
        // if (selectedVoice) utterance.voice = selectedVoice;
        
        // Speak
        synth.speak(utterance);
    }

    // --- Rendering Functions ---
    function renderStatusLine() {
        // Make sure toggle is preserved
        const statusBar = statusLineSection.querySelector('.status-bar');
        if (!document.getElementById('status-section-toggle') && statusBar) {
            const wasCollapsed = statusLineSection.classList.contains('collapsed');
            const toggle = document.createElement('div');
            toggle.classList.add('section-toggle');
            toggle.id = 'status-section-toggle';
            toggle.title = 'Expand/Collapse Status Section';
            toggle.classList.add(wasCollapsed ? 'collapsed' : 'expanded');
            toggle.addEventListener('click', () => toggleSection(statusLineSection, toggle));
            
            // Insert at the beginning of status bar
            statusBar.insertBefore(toggle, statusBar.firstChild);
        }

        // Websocket Dot
        websocketStatusDot.style.backgroundColor = connectionStatus === 'open' ? 'var(--status-online)' 
                                                  : (connectionStatus === 'connecting' ? 'var(--status-connecting)' : 'var(--status-error)');
        websocketStatusDot.title = `WebSocket: ${connectionStatus}`;                                                  

        // Backend Dot (Simplified for now - assume OK if WS is open and we have data)
        backendApiStatus = (connectionStatus === 'open' && userData.size > 0) ? 'ok' : 'unknown'; // Basic check
        backendStatusDot.style.backgroundColor = backendApiStatus === 'ok' ? 'var(--status-online)' : 'var(--status-other)';
        backendStatusDot.title = `Backend API: ${backendApiStatus}`; 

        // Timestamp & Message
        lastUpdateTimeSpan.textContent = `Last Update: ${lastUpdateTime ? formatTimestamp(lastUpdateTime) : '--:--:--'}`;
        statusMessageSpan.textContent = statusMessage;
    }

    // Helper function to create sortable user array
    function createSortableUserArray() {
        const usersToRender = [];
        
        log('debug', `Creating sortable array from ${userOrder.length} users in userOrder`);
        
        userOrder.forEach((uid, index) => {
            const userStateData = userData.get(uid);
            if (!userStateData || !userStateData.latestState) {
                log('warn', `User data missing or invalid for uid: ${uid}`);
                return;
            }
            
            const latestState = userStateData.latestState;
            const displayStatus = determineDisplayStatus(latestState);
            
            // Add to array with priority info (index in userOrder = priority from config)
            usersToRender.push({
                uid: uid,
                state: latestState,
                status: displayStatus,
                priority: index // Lower index = higher priority
            });
            
            log('debug', `Added user ${latestState.hrToken} (${uid}) with status ${displayStatus}`);
        });
        
        return usersToRender;
    }
    
    // Helper function to sort users by status and priority
    function sortUsersByStatusAndPriority(users) {
        return users.sort((a, b) => {
            // First compare by status
            const statusDiff = statusPriority[a.status] - statusPriority[b.status];
            if (statusDiff !== 0) return statusDiff;
            
            // If status is the same, sort by priority (from config)
            return a.priority - b.priority;
        });
    }

    function renderQuickStatusBar() {
        log('debug', `Starting renderQuickStatusBar. UserOrder length: ${userOrder.length}, userData size: ${userData.size}`);
        
        // Save the toggle state before clearing
        const wasCollapsed = quickStatusSection.classList.contains('collapsed');
        
        // Clear the container
        quickStatusBar.innerHTML = ''; 
        
        // Add toggle back
        const toggle = document.createElement('div');
        toggle.classList.add('section-toggle', 'dark');
        toggle.id = 'quick-status-section-toggle';
        toggle.title = 'Expand/Collapse Quick Status Section';
        toggle.classList.add(wasCollapsed ? 'collapsed' : 'expanded');
        toggle.addEventListener('click', () => toggleSection(quickStatusSection, toggle));
        quickStatusBar.appendChild(toggle);
        
        // Create and sort the users array
        const usersToRender = createSortableUserArray();
        log('debug', `Created sortable array with ${usersToRender.length} users`);
        
        sortUsersByStatusAndPriority(usersToRender);
        
        // Render in sorted order
        usersToRender.forEach(userData => {
            const item = document.createElement('div');
            item.classList.add('user-status-item');
            item.dataset.userId = userData.uid;

            Object.values(statusClasses).forEach(cls => item.classList.remove(cls)); 
            item.classList.add(statusClasses[userData.status] || statusClasses['OTHER']);

            const img = document.createElement('img');
            img.src = getUserIconUrl(userData.state.user);
            img.alt = `${userData.state.hrToken}'s icon`;
            img.onerror = () => { img.src = getUserIconUrl(null); }; 

            const nameSpan = document.createElement('span');
            nameSpan.textContent = userData.state.hrToken;

            item.appendChild(img);
            item.appendChild(nameSpan);
            quickStatusBar.appendChild(item);
        });
    }

    function renderTimeline() {
        timelineUsers.innerHTML = ''; 
        log('info', 'Rendering timeline (basic structure)...');
        
        // Create and sort the users array
        const usersToRender = createSortableUserArray();
        sortUsersByStatusAndPriority(usersToRender);
        
        // Render in sorted order
        usersToRender.forEach(userData => {
            const row = document.createElement('div');
            row.classList.add('timeline-user-row');
            row.dataset.userId = userData.uid;

            const idArea = document.createElement('div');
            idArea.classList.add('id-area');
            
            const bgColor = statusColors[userData.status] || statusColors['OTHER']; 
            idArea.style.backgroundColor = bgColor;
            if (userData.status === 'OFFLINE' || userData.status === 'ERROR') {
                idArea.style.color = 'white';
            } else {
                idArea.style.color = 'black';
            }

            const img = document.createElement('img');
            img.src = getUserIconUrl(userData.state.user);
            img.alt = '';
            img.onerror = () => { img.src = getUserIconUrl(null); }; 
            const nameSpan = document.createElement('span');
            nameSpan.textContent = userData.state.hrToken;
            idArea.appendChild(img);
            idArea.appendChild(nameSpan);

            const graphArea = document.createElement('div');
            graphArea.classList.add('graph-area');
            graphArea.textContent = 'Graph Placeholder'; 

            row.appendChild(idArea);
            row.appendChild(graphArea);
            timelineUsers.appendChild(row);
        });
    }

    function updateUI() {
        renderStatusLine();
        renderQuickStatusBar();
        renderTimeline();
    }

    // --- WebSocket Handling ---
    function connectWebSocket() {
        // Clear any active countdown
        if (countdownInterval) {
            clearInterval(countdownInterval);
            countdownInterval = null;
        }
        
        // Clear any scheduled reconnect
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
    
        log('info', `Attempting to connect to ${wsUri}...`);
        connectionStatus = 'connecting';
        statusMessage = 'Connecting...';
        updateUI();

        // Update reconnection message if it exists
        if (disconnectionMessage) {
            const reconnectInfo = disconnectionMessage.querySelector('.reconnect-info');
            if (reconnectInfo) {
                reconnectInfo.textContent = 'Connecting...';
            }
        }

        websocket = new WebSocket(wsUri);

        websocket.onopen = (event) => {
            log('info', 'WebSocket connection established.');
            connectionStatus = 'open';
            statusMessage = 'WebSocket connected. Waiting for data...';
            renderStatusLine();
            
            // Reset reconnection attempts on successful connection
            reconnectAttempts = 0;
            
            // Reset shutdown state
            isServerShuttingDown = false;
            
            // Re-enable control buttons
            const shutdownBtn = document.getElementById('shutdown-server-btn');
            const refreshBtn = document.getElementById('refresh-button');
            if (shutdownBtn) shutdownBtn.disabled = false;
            if (refreshBtn) refreshBtn.disabled = false;
            
            // Refresh admin button handlers
            refreshAdminButtonHandlers();
            
            // Remove any disconnection message if present
            removeAllShutdownMessages();
            disconnectionMessage = null;
            
            // Log client-side connection
            addLogEntry('client-response', 'WebSocket connection established');
            
            // Request a refresh immediately to get latest session status and data
            log('info', 'Sending REFRESH request after WebSocket connection established');
            websocket.send('REFRESH');
        };

        websocket.onclose = (event) => {
            log('warn', `WebSocket connection closed: Code=${event.code}, Reason='${event.reason}'`);
            connectionStatus = 'closed';
            statusMessage = `Disconnected: ${event.reason || 'Connection closed'}`;
            backendApiStatus = 'unknown';
            updateUI();
            
            // Log client-side disconnection
            addLogEntry('client-response', `WebSocket connection closed: Code=${event.code}, Reason='${event.reason}'`);
            
            // Handle the disconnection differently based on whether the server is shutting down
            if (isServerShuttingDown) {
                // Server is shutting down, show disconnection message after a delay
                setTimeout(() => {
                    // Convert the existing shutdown message to the unavailable message
                    // rather than creating a new one
                    showServerUnavailableMessage();
                    
                    // Start the reconnection sequence
                    scheduleReconnect();
                }, 5000); // 5 second delay to show shutdown message first
            } else {
                // Normal disconnection (not from shutdown), try to reconnect quickly
                setTimeout(connectWebSocket, 5000);
            }
        };

        websocket.onerror = (event) => {
            log('error', 'WebSocket error observed.');
            console.error('WebSocket Error Event:', event);
            connectionStatus = 'error';
            statusMessage = 'Connection error!';
            backendApiStatus = 'unknown';
            
            // Log client-side error
            addLogEntry('client-response', 'WebSocket connection error');
            
            // Don't update UI immediately on error, wait for onclose to trigger reconnect logic
        };

        websocket.onmessage = (event) => {
            log('debug', `WebSocket message received: ${event.data}`);
            lastUpdateTime = new Date().toISOString(); // Track last interaction
            statusMessage = 'Operational'; // Assume operational if messages are coming
            
            // Log client-side message reception
            addLogEntry('client-response', `Received: ${event.data}`);

            try {
                const message = JSON.parse(event.data);
                log('debug', `Parsed message type: ${message.type}`);
                
                switch (message.type) {
                    case 'INITIAL_STATE':
                        // Store all users from initial state
                        if (Array.isArray(message.payload)) {
                            // Clear existing data to avoid stale entries
                            userData.clear();
                            userOrder = [];
                            
                            message.payload.forEach(userStatus => {
                                // Store user data with proper structure
                                userData.set(userStatus.vrcUid, { latestState: userStatus, previousState: null });
                                
                                // Store vrcUid in userOrder (not hrToken)
                                if (userStatus.vrcUid && !userOrder.includes(userStatus.vrcUid)) {
                                    userOrder.push(userStatus.vrcUid);
                                }
                            });
                            
                            // Get server start time from metadata
                            if (message.metadata && message.metadata.serverStartTime) {
                                serverStartTime = new Date(message.metadata.serverStartTime);
                                log('info', `Server start time: ${serverStartTime.toISOString()}`);
                            }
                            
                            // Add debug info
                            log('info', `Received ${userData.size} users in initial state`);
                            log('debug', `UserOrder: ${JSON.stringify(userOrder)}`);
                            
                            // Redraw all UI elements
                            updateUI();
                        }
                        break;
                    case 'SESSION_STATUS':
                        log('info', `Processing SESSION_STATUS message: ${JSON.stringify(message.payload)}`);
                        handleSessionStatus(message.payload);
                        break;
                    case 'USER_UPDATE':
                        const updatedStateDTO = message.payload;
                        log('info', `Processing USER_UPDATE for ${updatedStateDTO.hrToken} (${updatedStateDTO.vrcUid})`);
                        
                        const previousData = userData.get(updatedStateDTO.vrcUid);
                        const previousState = previousData ? previousData.latestState : null;
                        
                        userData.set(updatedStateDTO.vrcUid, { latestState: updatedStateDTO, previousState: previousState });
                        
                        // Make sure this user's vrcUid is in userOrder
                        if (!userOrder.includes(updatedStateDTO.vrcUid)) {
                            userOrder.push(updatedStateDTO.vrcUid);
                            log('info', `Added new user ${updatedStateDTO.hrToken} to userOrder`);
                        }
                        
                        announceStatusChange(previousState, updatedStateDTO);

                        // Check if status changed, which would require re-sorting
                        const oldStatus = previousState ? determineDisplayStatus(previousState) : null;
                        const newStatus = determineDisplayStatus(updatedStateDTO);
                        const statusChanged = oldStatus !== newStatus;
                        
                        // If status changed, do a full redraw to maintain sort order
                        if (statusChanged) {
                            log('debug', `Status changed from ${oldStatus} to ${newStatus}, performing full redraw`);
                            renderQuickStatusBar();
                            renderTimeline();
                        } else {
                            // Just update the individual items
                            const quickStatusItem = quickStatusBar.querySelector(`.user-status-item[data-user-id="${updatedStateDTO.vrcUid}"]`);
                            if (quickStatusItem) {
                                Object.values(statusClasses).forEach(cls => quickStatusItem.classList.remove(cls));
                                quickStatusItem.classList.add(statusClasses[newStatus] || statusClasses['OTHER']);
                                const img = quickStatusItem.querySelector('img');
                                if (img) img.src = getUserIconUrl(updatedStateDTO.user);
                                const span = quickStatusItem.querySelector('span');
                                if (span) span.textContent = updatedStateDTO.hrToken;
                            } else {
                                log('warn', `Quick status item not found for ${updatedStateDTO.hrToken} - performing full redraw.`);
                                renderQuickStatusBar();
                            }
                            
                            const timelineIdArea = timelineUsers.querySelector(`.timeline-user-row[data-user-id="${updatedStateDTO.vrcUid}"] .id-area`);
                            if(timelineIdArea){
                                const bgColor = statusColors[newStatus] || statusColors['OTHER']; 
                                timelineIdArea.style.backgroundColor = bgColor;
                                const img = timelineIdArea.querySelector('img');
                                if (img) img.src = getUserIconUrl(updatedStateDTO.user);
                                const span = timelineIdArea.querySelector('span');
                                if (span) span.textContent = updatedStateDTO.hrToken;

                                if (newStatus === 'OFFLINE' || newStatus === 'ERROR') {
                                    timelineIdArea.style.color = 'white';
                                } else {
                                    timelineIdArea.style.color = 'black';
                                }
                            } else {
                                log('warn', `Timeline row not found for ${updatedStateDTO.hrToken} - performing full redraw.`);
                                renderTimeline();
                            }
                        }
                        
                        renderStatusLine();
                        break;
                    case 'SYSTEM':
                        log('info', `Processing SYSTEM message: ${JSON.stringify(message.payload)}`);
                        if (message.payload && message.payload.action === 'SHUTDOWN') {
                            // Remove any existing shutdown/disconnection messages first
                            removeAllShutdownMessages();
                            
                            // Mark that we're in a shutdown state
                            isServerShuttingDown = true;
                            
                            // Display shutdown message
                            disconnectionMessage = document.createElement('div');
                            disconnectionMessage.className = 'shutdown-message disconnection-message';
                            disconnectionMessage.innerHTML = `
                                <h3>Server Shutdown Initiated</h3>
                                <p>The server is shutting down. Waiting for server to disconnect...</p>
                                <p>You may close this window.</p>
                            `;
                            document.body.appendChild(disconnectionMessage);
                            
                            // Make the message draggable
                            makeDraggable(disconnectionMessage);
                            
                            // Disable all interactive elements
                            document.getElementById('shutdown-server-btn').disabled = true;
                            document.getElementById('refresh-button').disabled = true;
                            
                            // Update status
                            connectionStatus = 'closed';
                            statusMessage = 'Server is shutting down...';
                            renderStatusLine();
                            
                            // Don't attempt to reconnect immediately
                            websocket.onclose = () => {
                                log('info', 'WebSocket closed due to server shutdown.');
                                
                                // After a short delay, show the server unavailable message
                                setTimeout(() => {
                                    showServerUnavailableMessage();
                                    scheduleReconnect();
                                }, 5000); // 5 second delay to show shutdown message first
                            };
                        }
                        break;
                    case 'LOG_ENTRY':
                        // Handle server-side logs
                        if (message.payload) {
                            const logData = message.payload;
                            console.debug('Received LOG_ENTRY:', logData);
                            try {
                                // Check for timestamp and convert
                                let timestamp = new Date();
                                if (logData.timestamp) {
                                    // Now we're expecting a millisecond timestamp as a number
                                    if (typeof logData.timestamp === 'number') {
                                        timestamp = new Date(logData.timestamp);
                                        console.debug(`Using numeric timestamp: ${logData.timestamp} -> ${timestamp.toISOString()}`);
                                    } else if (typeof logData.timestamp === 'string') {
                                        // Handle string timestamp (fallback)
                                        timestamp = new Date(logData.timestamp);
                                        console.debug(`Using string timestamp: ${logData.timestamp} -> ${timestamp.toISOString()}`);
                                    }
                                } else {
                                    console.debug('No timestamp found in log entry, using current time');
                                }
                                
                                if (logData.type === 'request') {
                                    addLogEntry('server-request', logData.content, timestamp);
                                    log('debug', `Added server request log with timestamp ${timestamp.toISOString()}`);
                                } else if (logData.type === 'response') {
                                    addLogEntry('server-response', logData.content, timestamp);
                                    log('debug', `Added server response log with timestamp ${timestamp.toISOString()}`);
                                } else {
                                    log('warn', `Unknown log entry type: ${logData.type}`);
                                }
                            } catch (logError) {
                                log('error', `Error processing log entry: ${logError.message}`);
                                console.error('Log entry processing error:', logError, 'Original data:', logData);
                            }
                        } else {
                            log('warn', 'Received LOG_ENTRY message with no payload');
                        }
                        break;
                    case 'ERROR':
                         log('error', `Received backend error: ${JSON.stringify(message.payload)}`);
                         statusMessage = `Backend Error: ${message.payload.message || 'Unknown'}`;
                         renderStatusLine();
                         break;
                    default:
                        // Check for valid enum values from server that we might not be handling yet
                        const validTypes = [
                            'INITIAL_STATE', 'USER_UPDATE', 'ERROR', 'CLIENT_REQUEST', 
                            'SYSTEM', 'LOG_ENTRY', 'SESSION_STATUS', 'LOGIN_REQUIRED', 'LOGIN_RESULT'
                        ];
                        
                        if (validTypes.includes(message.type)) {
                            log('warn', `Received valid but unhandled message type: ${message.type}`);
                            console.warn('Unhandled message:', message);
                        } else {
                            log('error', `Received unknown WebSocket message type: ${message.type}`);
                            console.error('Unknown message:', message);
                        }
                }
            } catch (error) {
                log('error', `Failed to parse WebSocket message or update UI: ${error}`);
                console.error(error);
            }
        };
    }

    // Handle session status updates from server
    function handleSessionStatus(sessionStatus) {
        const wasLoggedIn = hasActiveSession;
        hasActiveSession = sessionStatus.hasActiveSession;
        lastSessionTime = sessionStatus.lastSessionTimeMs ? new Date(sessionStatus.lastSessionTimeMs) : null;
        
        log('info', `Session status update: hasActiveSession=${hasActiveSession}, lastSessionTime=${lastSessionTime}`);
        
        // Update UI based on session status
        if (hasActiveSession) {
            // If we were previously disconnected, hide the overlay
            if (!wasLoggedIn) {
                log('info', 'Session became active, hiding disconnected overlay');
                hideDisconnectedOverlay();
                hideLoginDialog();
                // Update status message
                statusMessage = 'Connected with active session';
                
                // Force immediate UI update to reflect the new session status
                renderStatusLine();
                
                // Trigger a refresh to get latest user states
                if (websocket && websocket.readyState === WebSocket.OPEN) {
                    log('info', 'Sending REFRESH request after login or session restoration...');
                    websocket.send('REFRESH');
                }
            }
        } else {
            // No active session, show disconnected overlay if not already visible
            statusMessage = 'No active session';
            if (!disconnectedOverlay && !loginDialogVisible) {
                log('info', 'Session not active, showing disconnected overlay');
                showDisconnectedOverlay();
            }
        }
        
        renderStatusLine();
    }
    
    // Show overlay indicating server has no active session
    function showDisconnectedOverlay() {
        // Remove any existing overlay first
        hideDisconnectedOverlay();
        
        // Create the overlay
        disconnectedOverlay = document.createElement('div');
        disconnectedOverlay.className = 'server-disconnected-overlay';
        disconnectedOverlay.innerHTML = `
            <h3>VRChat Session Required</h3>
            <p>The server is not connected to VRChat.</p>
            <p id="overlay-disconnected-time">Disconnected for: 00:00:00</p>
            <button id="overlay-login-button">Login</button>
        `;
        
        document.body.appendChild(disconnectedOverlay);
        
        // Make the overlay draggable
        makeDraggable(disconnectedOverlay);
        
        // Add login button handler
        const loginButton = document.getElementById('overlay-login-button');
        loginButton.addEventListener('click', showLoginDialog);
        
        // Start timer to update the disconnected time
        startDisconnectedTimer();
    }
    
    // Make an element draggable
    function makeDraggable(element) {
        let pos1 = 0, pos2 = 0, pos3 = 0, pos4 = 0;
        
        element.onmousedown = dragMouseDown;
        
        function dragMouseDown(e) {
            e = e || window.event;
            e.preventDefault();
            // Get the mouse cursor position at startup
            pos3 = e.clientX;
            pos4 = e.clientY;
            document.onmouseup = closeDragElement;
            // Call a function whenever the cursor moves
            document.onmousemove = elementDrag;
        }
        
        function elementDrag(e) {
            e = e || window.event;
            e.preventDefault();
            // Calculate the new cursor position
            pos1 = pos3 - e.clientX;
            pos2 = pos4 - e.clientY;
            pos3 = e.clientX;
            pos4 = e.clientY;
            // Set the element's new position
            element.style.top = (element.offsetTop - pos2) + "px";
            element.style.left = (element.offsetLeft - pos1) + "px";
        }
        
        function closeDragElement() {
            // Stop moving when mouse button is released
            document.onmouseup = null;
            document.onmousemove = null;
        }
    }
    
    // Hide the disconnected overlay
    function hideDisconnectedOverlay() {
        if (disconnectedOverlay) {
            document.body.removeChild(disconnectedOverlay);
            disconnectedOverlay = null;
        }
        
        // Clear the timer
        if (disconnectedTimer) {
            clearInterval(disconnectedTimer);
            disconnectedTimer = null;
        }
    }
    
    // Start timer to update disconnected time
    function startDisconnectedTimer() {
        // Clear any existing timer
        if (disconnectedTimer) {
            clearInterval(disconnectedTimer);
        }
        
        const updateDisconnectedTime = () => {
            // Update in disconnected overlay
            const overlayTimeElement = document.getElementById('overlay-disconnected-time');
            
            // Update in login dialog
            const sessionInfoTime = document.getElementById('disconnected-time');
            
            if (!overlayTimeElement && !sessionInfoTime) {
                // If neither element exists, stop the timer
                if (disconnectedTimer) {
                    clearInterval(disconnectedTimer);
                    disconnectedTimer = null;
                }
                return;
            }
            
            let timeSince;
            if (lastSessionTime) {
                // Calculate time since last session
                timeSince = new Date() - new Date(lastSessionTime);
            } else {
                // If no last session time, use server start time or client start time
                timeSince = new Date() - (serverStartTime || startTime);
            }
            
            // Format as dd:hh:mm:ss
            const days = Math.floor(timeSince / (1000 * 60 * 60 * 24));
            const hours = Math.floor((timeSince % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
            const minutes = Math.floor((timeSince % (1000 * 60 * 60)) / (1000 * 60));
            const seconds = Math.floor((timeSince % (1000 * 60)) / 1000);
            
            const timeString = `${String(days).padStart(2, '0')}:${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
            
            // Update overlay if it exists
            if (overlayTimeElement) {
                overlayTimeElement.textContent = `Disconnected for: ${timeString}`;
            }
            
            // Update login dialog if it exists
            if (sessionInfoTime) {
                sessionInfoTime.textContent = `Disconnected for: ${timeString}`;
            }
        };
        
        // Update immediately and then every second
        updateDisconnectedTime();
        disconnectedTimer = setInterval(updateDisconnectedTime, 1000);
    }
    
    // Show the login dialog
    function showLoginDialog() {
        // Hide the disconnected overlay if visible
        hideDisconnectedOverlay();
        
        // Show the login form, hide 2FA form
        document.getElementById('login-form').style.display = 'block';
        document.getElementById('twofa-form').style.display = 'none';
        document.getElementById('session-info').style.display = 'block';
        
        // Clear any previous error messages
        document.getElementById('error-message').textContent = '';
        document.getElementById('twofa-error-message').textContent = '';
        
        // Reset form fields
        document.getElementById('username').value = '';
        document.getElementById('password').value = '';
        document.getElementById('twofa-code').value = '';
        
        // Show the dialog
        const loginDialog = document.getElementById('login-dialog');
        loginDialog.style.display = 'flex';
        loginDialogVisible = true;
        
        // Start the disconnected timer for the login dialog
        startDisconnectedTimer();
        
        // Focus on the username field
        setTimeout(() => {
            document.getElementById('username').focus();
        }, 100);
    }
    
    // Hide the login dialog
    function hideLoginDialog() {
        const loginDialog = document.getElementById('login-dialog');
        loginDialog.style.display = 'none';
        loginDialogVisible = false;
        twoFactorRequired = false;
    }
    
    // Submit login credentials
    function submitLogin() {
        if (twoFactorRequired) {
            // Submit 2FA code
            const code = document.getElementById('twofa-code').value.trim();
            if (!code) {
                document.getElementById('twofa-error-message').textContent = 'Please enter your 2FA code';
                return;
            }
            
            log('info', 'Submitting 2FA code...');
            
            // Create the login request
            const loginRequest = {
                type: '2fa',
                twoFactorCode: code
            };
            
            // Log client request
            addLogEntry('client-request', `Sending 2FA verification: ${JSON.stringify({...loginRequest, twoFactorCode: '***'})}`);
            
            // Send the request
            fetch('/api/auth/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(loginRequest)
            })
            .then(response => response.json())
            .then(data => {
                // Log response
                log('info', `2FA result: ${JSON.stringify(data)}`);
                addLogEntry('client-response', `2FA Result: ${JSON.stringify(data)}`);
                
                if (data.success) {
                    // Success! Hide dialog
                    log('info', '2FA verification successful');
                    hideLoginDialog();
                } else {
                    // Failed, show error
                    log('warn', `2FA verification failed: ${data.message}`);
                    document.getElementById('twofa-error-message').textContent = data.message || 'Invalid 2FA code';
                }
            })
            .catch(error => {
                console.error('Error submitting 2FA:', error);
                log('error', `2FA error: ${error.message}`);
                addLogEntry('client-response', `2FA Error: ${error.message}`);
                document.getElementById('twofa-error-message').textContent = 'Network error. Please try again.';
            });
        } else {
            // Submit username/password
            const username = document.getElementById('username').value.trim();
            const password = document.getElementById('password').value;
            
            if (!username || !password) {
                document.getElementById('error-message').textContent = 'Please enter both username and password';
                return;
            }
            
            log('info', `Submitting login for user: ${username}`);
            
            // Create the login request
            const loginRequest = {
                type: 'credentials',
                username: username,
                password: password
            };
            
            // Log client request (don't log password)
            addLogEntry('client-request', `Sending login request: ${JSON.stringify({...loginRequest, password: '***'})}`);
            
            // Send the request
            fetch('/api/auth/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(loginRequest)
            })
            .then(response => response.json())
            .then(data => {
                // Log response
                log('info', `Login result: ${JSON.stringify(data)}`);
                addLogEntry('client-response', `Login Result: ${JSON.stringify(data)}`);
                
                if (data.success) {
                    // Success! Hide dialog
                    log('info', 'Login successful');
                    hideLoginDialog();
                } else if (data.requires2FA) {
                    // 2FA required, show 2FA form
                    log('info', `2FA required, type: ${data.twoFactorType}`);
                    document.getElementById('login-form').style.display = 'none';
                    document.getElementById('twofa-form').style.display = 'block';
                    document.getElementById('twofa-code').focus();
                    twoFactorRequired = true;
                } else {
                    // Failed, show error
                    log('warn', `Login failed: ${data.message}`);
                    document.getElementById('error-message').textContent = data.message || 'Invalid credentials';
                }
            })
            .catch(error => {
                console.error('Error submitting login:', error);
                log('error', `Login error: ${error.message}`);
                addLogEntry('client-response', `Login Error: ${error.message}`);
                document.getElementById('error-message').textContent = 'Network error. Please try again.';
            });
        }
    }

    // --- Event Listeners ---
    statusSectionToggle.addEventListener('click', () => {
        toggleSection(statusLineSection, statusSectionToggle);
    });
    
    quickStatusSectionToggle.addEventListener('click', () => {
        toggleSection(quickStatusSection, quickStatusSectionToggle);
    });
    
    timelineSectionToggle.addEventListener('click', () => {
        toggleSection(timelineContainer, timelineSectionToggle);
    });
    
    refreshButton.addEventListener('click', () => {
        if (websocket && websocket.readyState === WebSocket.OPEN) {
            log('info', 'Sending REFRESH request...');
            addLogEntry('client-request', 'Sending REFRESH command to server');
            websocket.send('REFRESH');
        } else {
            log('warn', 'Cannot refresh: WebSocket not connected.');
            addLogEntry('client-request', 'Attempted REFRESH but WebSocket not connected', new Date());
        }
    });

    timeScaleSelect.addEventListener('change', (event) => {
         timeScale = parseInt(event.target.value, 10);
         log('info', `Time scale changed to ${timeScale}s/px`);
         // TODO: Trigger timeline redraw with new scale
         renderTimeline(); // For now, just redraw placeholder structure
    });

    // --- Section Toggle Handlers ---
    function toggleSection(section, toggle) {
        const isExpanded = section.classList.contains('expanded');
        
        if (isExpanded) {
            section.classList.remove('expanded');
            section.classList.add('collapsed');
            toggle.classList.remove('expanded');
            toggle.classList.add('collapsed');
            
            // Stop uptime interval when closing
            if (section === statusLineSection && uptimeInterval) {
                clearInterval(uptimeInterval);
                uptimeInterval = null;
            }
        } else {
            section.classList.remove('collapsed');
            section.classList.add('expanded');
            toggle.classList.remove('collapsed');
            toggle.classList.add('expanded');
            
            // Start uptime interval when opening
            if (section === statusLineSection) {
                startUptimeCounter();
                
                // Update log console if it's the active tab
                if (document.getElementById('log-console').classList.contains('active')) {
                    updateLogConsole();
                }
            }
        }
    }
    
    // --- Tab Handling Functions ---
    function setupTabHandling() {
        tabButtons.forEach(button => {
            button.addEventListener('click', () => {
                const tabId = button.getAttribute('data-tab');
                
                // Update active tab button
                tabButtons.forEach(btn => btn.classList.remove('active'));
                button.classList.add('active');
                
                // Update active tab pane
                tabPanes.forEach(pane => {
                    pane.classList.remove('active');
                    if (pane.id === tabId) {
                        pane.classList.add('active');
                    }
                });
            });
        });
    }
    
    // --- Admin Functions ---
    function startUptimeCounter() {
        if (uptimeInterval) {
            clearInterval(uptimeInterval);
        }
        
        const updateUptime = () => {
            if (!serverStartTime) {
                // If server start time isn't available yet, show "Unknown"
                document.getElementById('uptime-days').textContent = '?';
                document.getElementById('uptime-hours').textContent = '?';
                document.getElementById('uptime-seconds').textContent = 'Unknown';
                return;
            }
            
            const now = new Date();
            const diff = now - serverStartTime;
            
            const days = Math.floor(diff / (1000 * 60 * 60 * 24));
            const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
            const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
            const seconds = Math.floor((diff % (1000 * 60)) / 1000);
            
            document.getElementById('uptime-days').textContent = days;
            document.getElementById('uptime-hours').textContent = hours;
            document.getElementById('uptime-seconds').textContent = `${minutes}m ${seconds}s`;
        };
        
        // Update immediately and then every second
        updateUptime();
        uptimeInterval = setInterval(updateUptime, 1000);
    }
    
    // --- Initialize Section States ---
    function initializeSectionStates() {
        // Top section (status line) - initially collapsed
        statusLineSection.classList.remove('expanded');
        statusLineSection.classList.add('collapsed');
        statusSectionToggle.classList.remove('expanded');
        statusSectionToggle.classList.add('collapsed');
        
        // Middle section (quick status) - initially collapsed
        quickStatusSection.classList.remove('expanded');
        quickStatusSection.classList.add('collapsed');
        quickStatusSectionToggle.classList.remove('expanded');
        quickStatusSectionToggle.classList.add('collapsed');
        
        // Bottom section (timeline) - initially expanded
        timelineContainer.classList.remove('collapsed');
        timelineContainer.classList.add('expanded');
        timelineSectionToggle.classList.remove('collapsed');
        timelineSectionToggle.classList.add('expanded');
    }
    
    // --- Logging Functions ---
    function sanitizeLogContent(content) {
        if (!content) return content;
        
        // Convert to string if it's not already
        const str = typeof content === 'string' ? content : JSON.stringify(content);
        
        // Patterns for sensitive information
        const sensitivePatterns = [
            // Password pattern (simple version)
            { regex: /"password"\s*:\s*"[^"]*"/gi, replacement: '"password":"###PASSWORD###"' },
            { regex: /password=[^&\s]*/gi, replacement: 'password=###PASSWORD###' },
            
            // Auth cookies and tokens
            { regex: /"auth(?:Token|Cookie)?"\s*:\s*"[^"]*"/gi, replacement: '"authToken":"###AUTH_TOKEN###"' },
            { regex: /auth(?:Token|Cookie)?=[^&\s]*/gi, replacement: 'authToken=###AUTH_TOKEN###' },
            
            // Session tokens
            { regex: /"session(?:Token|Id)?"\s*:\s*"[^"]*"/gi, replacement: '"sessionToken":"###SESSION_TOKEN###"' },
            { regex: /session(?:Token|Id)?=[^&\s]*/gi, replacement: 'sessionToken=###SESSION_TOKEN###' },
            
            // API keys
            { regex: /"api[_-]?key"\s*:\s*"[^"]*"/gi, replacement: '"api_key":"###API_KEY###"' },
            { regex: /api[_-]?key=[^&\s]*/gi, replacement: 'api_key=###API_KEY###' },
            
            // 2FA cookies
            { regex: /"twoFactorAuth(?:Token|Cookie)?"\s*:\s*"[^"]*"/gi, replacement: '"twoFactorAuth":"###2FA_TOKEN###"' },
            { regex: /twoFactorAuth(?:Token|Cookie)?=[^&\s]*/gi, replacement: 'twoFactorAuth=###2FA_TOKEN###' },
            
            // Basic auth headers
            { regex: /Basic\s+[A-Za-z0-9+/=]+/gi, replacement: 'Basic ###BASIC_AUTH###' },
            
            // Bearer tokens
            { regex: /Bearer\s+[A-Za-z0-9_.-]+/gi, replacement: 'Bearer ###BEARER_TOKEN###' }
        ];
        
        // Apply all sanitization patterns
        let sanitized = str;
        sensitivePatterns.forEach(pattern => {
            sanitized = sanitized.replace(pattern.regex, pattern.replacement);
        });
        
        return sanitized;
    }
    
    function addLogEntry(type, content, timestamp = new Date(), level = null) {
        // Sanitize content before storing
        const sanitizedContent = sanitizeLogContent(content);
        
        // Create log entry object with optional level for client logging
        const logEntry = {
            timestamp: timestamp,
            content: sanitizedContent,
            level: level  // Add level property for client logging
        };
        
        // Add to appropriate log array
        switch (type) {
            case 'client-request':
                logEntries.clientRequests.push(logEntry);
                // Trim if exceeds max size
                if (logEntries.clientRequests.length > MAX_LOGS_PER_CATEGORY) {
                    logEntries.clientRequests.shift();
                }
                break;
            case 'client-response':
                logEntries.clientResponses.push(logEntry);
                if (logEntries.clientResponses.length > MAX_LOGS_PER_CATEGORY) {
                    logEntries.clientResponses.shift();
                }
                break;
            case 'server-request':
                logEntries.serverRequests.push(logEntry);
                if (logEntries.serverRequests.length > MAX_LOGS_PER_CATEGORY) {
                    logEntries.serverRequests.shift();
                }
                break;
            case 'server-response':
                logEntries.serverResponses.push(logEntry);
                if (logEntries.serverResponses.length > MAX_LOGS_PER_CATEGORY) {
                    logEntries.serverResponses.shift();
                }
                break;
            case 'client-logging':
                logEntries.clientLogging.push(logEntry);
                if (logEntries.clientLogging.length > MAX_LOGS_PER_CATEGORY) {
                    logEntries.clientLogging.shift();
                }
                break;
        }
        
        // Update log display if log console is visible
        if (statusLineSection.classList.contains('expanded') && 
            document.getElementById('log-console').classList.contains('active')) {
            updateLogConsole();
        }
    }
    
    function updateLogConsole() {
        // Clear current logs
        logConsoleOutput.innerHTML = '';
        
        // Collect logs based on checkbox filters
        let logsToShow = [];
        
        if (clientRequestsCheckbox && clientRequestsCheckbox.checked) {
            logEntries.clientRequests.forEach(entry => {
                logsToShow.push({
                    timestamp: entry.timestamp,
                    type: 'client-request',
                    content: entry.content,
                    style: 'color: #8af'
                });
            });
        }
        
        if (clientResponsesCheckbox && clientResponsesCheckbox.checked) {
            logEntries.clientResponses.forEach(entry => {
                logsToShow.push({
                    timestamp: entry.timestamp,
                    type: 'client-response',
                    content: entry.content,
                    style: 'color: #8fa'
                });
            });
        }
        
        if (serverRequestsCheckbox && serverRequestsCheckbox.checked) {
            logEntries.serverRequests.forEach(entry => {
                logsToShow.push({
                    timestamp: entry.timestamp,
                    type: 'server-request',
                    content: entry.content,
                    style: 'color: #f9a'
                });
            });
        }
        
        if (serverResponsesCheckbox && serverResponsesCheckbox.checked) {
            logEntries.serverResponses.forEach(entry => {
                logsToShow.push({
                    timestamp: entry.timestamp,
                    type: 'server-response',
                    content: entry.content,
                    style: 'color: #fd8'
                });
            });
        }
        
        if (clientLoggingCheckbox && clientLoggingCheckbox.checked) {
            logEntries.clientLogging.forEach(entry => logsToShow.push({
                timestamp: entry.timestamp,
                type: 'client-logging',
                content: entry.content,
                level: entry.level,
                style: 'color: #fa8'
            }));
        }
        
        // Sort logs by timestamp
        logsToShow.sort((a, b) => a.timestamp - b.timestamp);
        
        // Create and append log entries
        logsToShow.forEach(log => {
            const logElement = document.createElement('div');
            logElement.className = 'log-entry';
            logElement.style = log.style;
            
            // Format timestamp
            const timestamp = log.timestamp.toISOString().replace('T', ' ').substr(0, 19);
            
            // Create prefix based on type
            let prefix;
            switch (log.type) {
                case 'client-request':
                    prefix = '[CR]';
                    break;
                case 'client-response':
                    prefix = '[CRes]';
                    break;
                case 'server-request':
                    prefix = '[SR]';
                    break;
                case 'server-response':
                    prefix = '[SRes]';
                    break;
                case 'client-logging':
                    prefix = `[CL:${log.level ? log.level.toUpperCase() : 'LOG'}]`;
                    break;
            }
            
            logElement.textContent = `${timestamp} ${prefix} ${log.content}`;
            logConsoleOutput.appendChild(logElement);
        });
        
        // Scroll to bottom if we're adding new content
        if (logsToShow.length > 0) {
            logConsoleOutput.scrollTop = logConsoleOutput.scrollHeight;
        }
    }

    // --- Shutdown Dialog Functions ---
    function setupShutdownDialog() {
        const dialog = document.getElementById('confirm-dialog');
        const confirmYesBtn = document.getElementById('confirm-yes');
        const confirmNoBtn = document.getElementById('confirm-no');
        const shutdownBtn = document.getElementById('shutdown-server-btn');
        
        // Make sure we're using a fresh event listener approach
        if (shutdownBtn) {
            // Remove any existing event listeners first
            const newShutdownBtn = shutdownBtn.cloneNode(true);
            shutdownBtn.parentNode.replaceChild(newShutdownBtn, shutdownBtn);
            
            // Add fresh event listener
            newShutdownBtn.addEventListener('click', () => {
                log('debug', 'Shutdown button clicked, showing confirmation dialog');
                showShutdownConfirmation();
            });
        }
        
        confirmYesBtn.addEventListener('click', () => {
            sendShutdownRequest();
            hideShutdownConfirmation();
        });
        
        confirmNoBtn.addEventListener('click', () => {
            hideShutdownConfirmation();
        });
        
        // Close dialog if clicked outside of it
        dialog.addEventListener('click', (event) => {
            if (event.target === dialog) {
                hideShutdownConfirmation();
            }
        });
    }
    
    function showShutdownConfirmation() {
        const dialog = document.getElementById('confirm-dialog');
        dialog.style.display = 'flex';
    }
    
    function hideShutdownConfirmation() {
        const dialog = document.getElementById('confirm-dialog');
        dialog.style.display = 'none';
    }

    // Function to send shutdown command - modified to log client request and track shutdown state
    function sendShutdownRequest() {
        if (websocket && websocket.readyState === WebSocket.OPEN) {
            log('info', 'Sending SHUTDOWN request to server...');
            statusMessage = 'Shutting down server...';
            renderStatusLine();
            
            // Mark that we're in a shutdown state
            isServerShuttingDown = true;
            
            // Create command object
            const shutdownCommand = {
                type: 'COMMAND',
                command: 'SHUTDOWN'
            };
            
            // Log client request
            addLogEntry('client-request', `Sending command: ${JSON.stringify(shutdownCommand)}`);
            
            // Send shutdown command via WebSocket
            websocket.send(JSON.stringify(shutdownCommand));
            
            // Clean up any existing messages
            removeAllShutdownMessages();
            
            // Display a message to the user
            disconnectionMessage = document.createElement('div');
            disconnectionMessage.className = 'shutdown-message disconnection-message';
            disconnectionMessage.innerHTML = `
                <h3>Server Shutdown Initiated</h3>
                <p>The server is shutting down. Waiting for server to disconnect...</p>
                <p>You may close this window.</p>
            `;
            document.body.appendChild(disconnectionMessage);
            
            // Make the message draggable
            makeDraggable(disconnectionMessage);
            
            // Disable controls
            document.getElementById('shutdown-server-btn').disabled = true;
            document.getElementById('refresh-button').disabled = true;
        } else {
            log('error', 'Cannot send shutdown request: WebSocket not connected');
            alert('Cannot send shutdown request: Not connected to server.');
        }
    }
    
    // Function to send a drop session request to the server
    function sendDropSessionRequest() {
        log('info', 'Sending DROP SESSION request to server...');
        statusMessage = 'Dropping VRChat session...';
        renderStatusLine();
        
        // Log client request
        addLogEntry('client-request', 'Sending request: POST /api/auth/logout');
        
        // Send the request to the server endpoint
        fetch('/api/auth/logout', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        })
        .then(response => {
            if (response.ok) {
                log('info', 'Session dropped successfully');
                addLogEntry('client-response', 'Session dropped successfully');
            } else {
                throw new Error(`Error: ${response.status} ${response.statusText}`);
            }
        })
        .catch(error => {
            log('error', `Failed to drop session: ${error.message}`);
            addLogEntry('client-response', `Failed to drop session: ${error.message}`);
            statusMessage = 'Failed to drop session';
            renderStatusLine();
        });
    }
    
    // Function to test speech announcements
    function sendTestAnnounceRequest() {
        // Test the speech announcement functionality at different volumes
        if (typeof synth === 'undefined') {
            alert('Speech synthesis is not supported in your browser!');
            return;
        }
        
        // Function to speak a test phrase at a given volume
        const speakTestPhrase = (status, volume) => {
            const utterance = new SpeechSynthesisUtterance(`Test user is now ${status}.`);
            utterance.volume = volume;
            log('info', `Test announcement: "${utterance.text}" at volume ${volume}`);
            synth.speak(utterance);
            
            // Add a slight delay between announcements
            return new Promise(resolve => setTimeout(resolve, 100));
        };
        
        // Queue the test announcements in sequence with different volumes
        // We use async/await to create small gaps between announcements
        (async () => {
            await speakTestPhrase('online', 1.0);
            await speakTestPhrase('offline', 0.66);
            await speakTestPhrase('on website', 0.33);
        })();
    }
    
    // Schedule a reconnection with exponential backoff
    function scheduleReconnect() {
        // Clear any existing reconnect timer
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
        }
        
        // Calculate delay with exponential backoff (capped at maxReconnectDelay)
        const delay = Math.min(reconnectIncrement * reconnectAttempts, maxReconnectDelay);
        
        log('info', `Scheduling reconnection attempt in ${delay / 1000} seconds (attempt #${reconnectAttempts + 1})`);
        
        // Set the reconnection time
        const reconnectTime = new Date(Date.now() + delay);
        
        // Start a countdown timer to update the message
        startReconnectCountdown(reconnectTime);
        
        // Schedule the reconnection
        reconnectTimer = setTimeout(() => {
            reconnectAttempts++;
            log('info', `Attempting reconnection #${reconnectAttempts}`);
            connectWebSocket();
        }, delay);
    }
    
    // Update the reconnection message with current delay
    function updateReconnectionMessage(secondsRemaining) {
        if (!disconnectionMessage) return;
        
        const minutes = Math.floor(secondsRemaining / 60);
        const seconds = secondsRemaining % 60;
        
        let timeDisplay;
        if (minutes > 0) {
            timeDisplay = `${minutes} minute${minutes !== 1 ? 's' : ''} ${seconds} second${seconds !== 1 ? 's' : ''}`;
        } else {
            timeDisplay = `${seconds} second${seconds !== 1 ? 's' : ''}`;
        }
            
        const reconnectInfo = disconnectionMessage.querySelector('.reconnect-info');
        if (reconnectInfo) {
            reconnectInfo.textContent = `Attempting to reconnect in ${timeDisplay}...`;
        }
    }
    
    // Start a countdown to the next reconnection attempt
    let countdownInterval = null;
    function startReconnectCountdown(reconnectTime) {
        // Clear any existing countdown
        if (countdownInterval) {
            clearInterval(countdownInterval);
        }
        
        // Function to update the countdown display
        const updateCountdown = () => {
            const now = new Date();
            const diffMs = reconnectTime - now;
            
            if (diffMs <= 0) {
                // Time's up, clear the interval
                clearInterval(countdownInterval);
                
                const reconnectInfo = disconnectionMessage?.querySelector('.reconnect-info');
                if (reconnectInfo) {
                    reconnectInfo.textContent = 'Attempting to reconnect...';
                }
                return;
            }
            
            // Calculate seconds remaining
            const secondsRemaining = Math.ceil(diffMs / 1000);
            updateReconnectionMessage(secondsRemaining);
        };
        
        // Update immediately, then every second
        updateCountdown();
        countdownInterval = setInterval(updateCountdown, 1000);
    }

    // Function to remove all shutdown/disconnection messages
    function removeAllShutdownMessages() {
        // Find and remove all shutdown and disconnection messages
        const messages = document.querySelectorAll('.shutdown-message, .server-disconnected-overlay');
        messages.forEach(message => {
            if (message && message.parentNode) {
                message.parentNode.removeChild(message);
            }
        });
        // Reset our reference if it was removed
        if (disconnectionMessage && !document.body.contains(disconnectionMessage)) {
            disconnectionMessage = null;
        }
    }

    // Show server unavailable message with reconnect button
    function showServerUnavailableMessage() {
        // Always remove any existing shutdown messages first
        removeAllShutdownMessages();
        
        // Create new disconnection message
        disconnectionMessage = document.createElement('div');
        disconnectionMessage.className = 'shutdown-message disconnection-message';
        disconnectionMessage.innerHTML = `
            <h3>Server Unavailable</h3>
            <p>The server is currently unavailable or has been shut down.</p>
            <p class="reconnect-info">Attempting to reconnect...</p>
            <button id="retry-connection-btn">Retry Now</button>
        `;
        
        document.body.appendChild(disconnectionMessage);
        
        // Make the message draggable
        makeDraggable(disconnectionMessage);
        
        // Add retry button handler
        const retryButton = document.getElementById('retry-connection-btn');
        retryButton.addEventListener('click', () => {
            log('info', 'Manual reconnection requested');
            // Reset the reconnection counter
            reconnectAttempts = 0;
            
            // Clear any scheduled reconnection
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            
            // Attempt to connect immediately
            connectWebSocket();
        });
    }

    // --- Initialize Everything ---
    function initialize() {
        // Clear any stale data
        userData.clear();
        userOrder = [];
        serverStartTime = null;
        
        // Reset connection management variables
        isServerShuttingDown = false;
        reconnectAttempts = 0;
        
        log('info', 'Initializing VRC Monitor UI');
        
        // Connect WebSocket
        connectWebSocket();
        
        // Setup UI interactions
        setupTabHandling();
        initializeSectionStates();
        setupShutdownDialog();
        setupLoginDialog();
        setupLogFilters();
        
        // Set up admin button click handlers - not shutdown, it's handled by setupShutdownDialog
        document.getElementById('test-announce-btn').addEventListener('click', () => {
            sendTestAnnounceRequest();
        });
        
        document.getElementById('drop-session-btn').addEventListener('click', () => {
            sendDropSessionRequest();
        });
        
        // Initial UI render
        renderStatusLine();
        renderTimeline(); // Basic structure only at this point
        
        // Start update timer - refreshes UI every 5 seconds to keep timestamps current
        setInterval(updateUI, 5000);
    }
    
    // --- Log Console Functions ---
    function setupLogFilters() {
        // Add event listeners to filter checkboxes
        if (clientRequestsCheckbox) {
            clientRequestsCheckbox.addEventListener('change', updateLogConsole);
        }
        if (clientResponsesCheckbox) {
            clientResponsesCheckbox.addEventListener('change', updateLogConsole);
        }
        if (serverRequestsCheckbox) {
            serverRequestsCheckbox.addEventListener('change', updateLogConsole);
        }
        if (serverResponsesCheckbox) {
            serverResponsesCheckbox.addEventListener('change', updateLogConsole);
        }
        if (clientLoggingCheckbox) {
            clientLoggingCheckbox.addEventListener('change', updateLogConsole);
        }
    }
    
    function setupLoginDialog() {
        const loginDialog = document.getElementById('login-dialog');
        const submitButton = document.getElementById('login-submit');
        const cancelButton = document.getElementById('login-cancel');
        
        // Submit button click
        submitButton.addEventListener('click', submitLogin);
        
        // Cancel button click
        cancelButton.addEventListener('click', () => {
            hideLoginDialog();
            // Show the disconnected overlay again
            if (!hasActiveSession) {
                showDisconnectedOverlay();
            }
        });
        
        // Handle enter key in input fields
        document.getElementById('username').addEventListener('keyup', (event) => {
            if (event.key === 'Enter') {
                document.getElementById('password').focus();
            }
        });
        
        document.getElementById('password').addEventListener('keyup', (event) => {
            if (event.key === 'Enter') {
                submitLogin();
            }
        });
        
        document.getElementById('twofa-code').addEventListener('keyup', (event) => {
            if (event.key === 'Enter') {
                submitLogin();
            }
        });
    }

    // Function to refresh admin button handlers
    function refreshAdminButtonHandlers() {
        log('debug', 'Refreshing admin button handlers');
        
        // Set up shutdown button via dialog setup
        setupShutdownDialog();
        
        // Set up other admin buttons
        const testAnnounceBtn = document.getElementById('test-announce-btn');
        const dropSessionBtn = document.getElementById('drop-session-btn');
        
        if (testAnnounceBtn) {
            const newTestBtn = testAnnounceBtn.cloneNode(true);
            testAnnounceBtn.parentNode.replaceChild(newTestBtn, testAnnounceBtn);
            newTestBtn.addEventListener('click', () => {
                sendTestAnnounceRequest();
            });
        }
        
        if (dropSessionBtn) {
            const newDropBtn = dropSessionBtn.cloneNode(true);
            dropSessionBtn.parentNode.replaceChild(newDropBtn, dropSessionBtn);
            newDropBtn.addEventListener('click', () => {
                sendDropSessionRequest();
            });
        }
    }

    // Set up console capture
    setupConsoleCapture();

    // Call initialize when document is loaded
    initialize();
}); 