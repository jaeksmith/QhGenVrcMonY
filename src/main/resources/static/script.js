document.addEventListener('DOMContentLoaded', () => {
    const wsUri = `ws://${window.location.host}/ws/status`;
    let websocket;
    let userData = new Map(); // Store user data by vrcUid { latestState: DTO, previousState: DTO }
    let userOrder = []; // Maintain order from config
    let connectionStatus = 'connecting'; // 'connecting', 'open', 'closed', 'error'
    let backendApiStatus = 'unknown'; // 'ok', 'error', 'unknown'
    let lastUpdateTime = null;
    let statusMessage = 'Initializing...';
    let timeScale = 1; // Default seconds per pixel

    // Tab handling variables
    const tabButtons = document.querySelectorAll('.tab-button');
    const tabPanes = document.querySelectorAll('.tab-pane');
    const logConsoleOutput = document.querySelector('.log-console-output');
    
    // For uptime simulation
    let startTime = new Date();
    let uptimeInterval;

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
    function log(level, message) {
        console[level](`[${new Date().toLocaleTimeString()}] ${message}`);
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
        if (!latestStateDTO) return 'ERROR'; 
        if (latestStateDTO.statusType === 'ERROR') return 'ERROR';
        if (!latestStateDTO.user || !latestStateDTO.user.state) return 'OTHER'; 
        
        const state = latestStateDTO.user.state.toLowerCase(); // Ensure lowercase for comparison
        const location = latestStateDTO.user.location?.toLowerCase(); // Use optional chaining and lowercase
        
        // Apply new logic
        if (state === 'online') {
            return 'ONLINE';
        } else if (state === 'active') {
            // Check location to differentiate online vs website
            return location !== 'offline' ? 'ONLINE' : 'ON_WEBSITE';
        } else if (state === 'offline') {
            return 'OFFLINE';
        } else {
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

    function renderQuickStatusBar() {
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
        
        userOrder.forEach(uid => {
            const userStateData = userData.get(uid);
            if (!userStateData || !userStateData.latestState) return; 
            const latestState = userStateData.latestState;

            const item = document.createElement('div');
            item.classList.add('user-status-item');
            item.dataset.userId = uid; 

            const displayStatus = determineDisplayStatus(latestState);
            Object.values(statusClasses).forEach(cls => item.classList.remove(cls)); 
            item.classList.add(statusClasses[displayStatus] || statusClasses['OTHER']);

            const img = document.createElement('img');
            img.src = getUserIconUrl(latestState.user);
            img.alt = `${latestState.hrToken}'s icon`;
            img.onerror = () => { img.src = getUserIconUrl(null); }; 

            const nameSpan = document.createElement('span');
            nameSpan.textContent = latestState.hrToken;

            item.appendChild(img);
            item.appendChild(nameSpan);
            quickStatusBar.appendChild(item);
        });
    }

    function renderTimeline() {
        timelineUsers.innerHTML = ''; 
         log('info', 'Rendering timeline (basic structure)...');
         
         userOrder.forEach(uid => {
             const userStateData = userData.get(uid);
             if (!userStateData || !userStateData.latestState) return;
             const latestState = userStateData.latestState;

             const row = document.createElement('div');
             row.classList.add('timeline-user-row');
             row.dataset.userId = uid;

             const idArea = document.createElement('div');
             idArea.classList.add('id-area');
             
             const displayStatus = determineDisplayStatus(latestState);
             const bgColor = statusColors[displayStatus] || statusColors['OTHER']; 
             idArea.style.backgroundColor = bgColor;
             if (displayStatus === 'OFFLINE' || displayStatus === 'ERROR') {
                 idArea.style.color = 'white';
             } else {
                 idArea.style.color = 'black';
             }

             const img = document.createElement('img');
             img.src = getUserIconUrl(latestState.user);
             img.alt = '';
             img.onerror = () => { img.src = getUserIconUrl(null); }; 
             const nameSpan = document.createElement('span');
             nameSpan.textContent = latestState.hrToken;
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
        log('info', `Attempting to connect to ${wsUri}...`);
        connectionStatus = 'connecting';
        statusMessage = 'Connecting...';
        updateUI();

        websocket = new WebSocket(wsUri);

        websocket.onopen = (event) => {
            log('info', 'WebSocket connection established.');
            connectionStatus = 'open';
            statusMessage = 'Connected. Waiting for initial state...';
            updateUI();
            // No need to send anything, server sends initial state automatically
        };

        websocket.onclose = (event) => {
            log('warn', `WebSocket connection closed: Code=${event.code}, Reason='${event.reason}'`);
            connectionStatus = 'closed';
            statusMessage = `Disconnected: ${event.reason || 'Connection closed'}`;
            backendApiStatus = 'unknown';
            updateUI();
            // Simple reconnect attempt after a delay
            setTimeout(connectWebSocket, 5000); 
        };

        websocket.onerror = (event) => {
            log('error', 'WebSocket error observed.');
            console.error('WebSocket Error Event:', event);
            connectionStatus = 'error';
            statusMessage = 'Connection error!';
            backendApiStatus = 'unknown';
            // Don't update UI immediately on error, wait for onclose to trigger reconnect logic
        };

        websocket.onmessage = (event) => {
            log('debug', `WebSocket message received: ${event.data}`);
            lastUpdateTime = new Date().toISOString(); // Track last interaction
            statusMessage = 'Operational'; // Assume operational if messages are coming

            try {
                const message = JSON.parse(event.data);
                switch (message.type) {
                    case 'INITIAL_STATE':
                        log('info', 'Processing INITIAL_STATE');
                        userData.clear();
                        userOrder = []; 
                        message.payload.forEach(userStateDTO => {
                            userData.set(userStateDTO.vrcUid, { latestState: userStateDTO, previousState: null });
                            userOrder.push(userStateDTO.vrcUid); 
                        });
                        updateUI();
                        break;
                    case 'USER_UPDATE':
                        const updatedStateDTO = message.payload;
                        log('info', `Processing USER_UPDATE for ${updatedStateDTO.hrToken}`);
                        
                        const previousData = userData.get(updatedStateDTO.vrcUid);
                        const previousState = previousData ? previousData.latestState : null;
                        
                        userData.set(updatedStateDTO.vrcUid, { latestState: updatedStateDTO, previousState: previousState });
                        
                        announceStatusChange(previousState, updatedStateDTO);

                        // --- UI Update Logic --- 
                        const quickStatusItem = quickStatusBar.querySelector(`.user-status-item[data-user-id="${updatedStateDTO.vrcUid}"]`);
                        if (quickStatusItem) {
                             const displayStatus = determineDisplayStatus(updatedStateDTO);
                             Object.values(statusClasses).forEach(cls => quickStatusItem.classList.remove(cls));
                             quickStatusItem.classList.add(statusClasses[displayStatus] || statusClasses['OTHER']);
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
                             const displayStatus = determineDisplayStatus(updatedStateDTO);
                             const bgColor = statusColors[displayStatus] || statusColors['OTHER']; 
                             timelineIdArea.style.backgroundColor = bgColor;
                             const img = timelineIdArea.querySelector('img');
                             if (img) img.src = getUserIconUrl(updatedStateDTO.user);
                             const span = timelineIdArea.querySelector('span');
                             if (span) span.textContent = updatedStateDTO.hrToken;

                             if (displayStatus === 'OFFLINE' || displayStatus === 'ERROR') {
                                 timelineIdArea.style.color = 'white';
                             } else {
                                 timelineIdArea.style.color = 'black';
                             }
                         } else {
                              log('warn', `Timeline row not found for ${updatedStateDTO.hrToken} - full redraw will handle.`);
                              renderTimeline();
                         }
                         renderStatusLine(); 
                        break;
                    case 'SYSTEM':
                        log('info', `Processing SYSTEM message: ${JSON.stringify(message.payload)}`);
                        if (message.payload && message.payload.action === 'SHUTDOWN') {
                            // Display shutdown message
                            const shutdownMessage = document.createElement('div');
                            shutdownMessage.className = 'shutdown-message';
                            shutdownMessage.innerHTML = '<h3>Server Shutdown In Progress</h3>' + 
                                '<p>The server is shutting down by administrator request.</p>' +
                                '<p>This page will no longer be functional. You may close this window.</p>';
                            document.body.appendChild(shutdownMessage);
                            
                            // Disable all interactive elements
                            document.getElementById('shutdown-server-btn').disabled = true;
                            document.getElementById('refresh-button').disabled = true;
                            
                            // Update status
                            connectionStatus = 'closed';
                            statusMessage = 'Server is shutting down...';
                            renderStatusLine();
                            
                            // Don't attempt to reconnect
                            websocket.onclose = () => {
                                log('info', 'WebSocket closed due to server shutdown.');
                            };
                        }
                        break;
                    case 'ERROR':
                         log('error', `Received backend error: ${JSON.stringify(message.payload)}`);
                         statusMessage = `Backend Error: ${message.payload.message || 'Unknown'}`;
                         renderStatusLine();
                         break;
                    default:
                        log('warn', `Received unknown WebSocket message type: ${message.type}`);
                }
            } catch (error) {
                log('error', `Failed to parse WebSocket message or update UI: ${error}`);
                console.error(error);
            }
        };
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
            websocket.send('REFRESH');
        } else {
            log('warn', 'Cannot refresh: WebSocket not connected.');
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
            
            // Start uptime interval and populate log console when opening
            if (section === statusLineSection) {
                startUptimeCounter();
                
                // Only populate log console if it's empty
                if (logConsoleOutput && logConsoleOutput.children.length === 0) {
                    populateSimulatedLogs();
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
    
    // --- Log Console Functions ---
    function populateSimulatedLogs() {
        // Clear existing logs
        logConsoleOutput.innerHTML = '';
        
        // Generate random number of log entries (20-50)
        const logCount = Math.floor(Math.random() * 30) + 20;
        
        // Log types and their prefixes
        const logTypes = [
            { type: 'Client Request', prefix: '[CR]', style: 'color: #8af' },
            { type: 'Client Response', prefix: '[CRes]', style: 'color: #8fa' },
            { type: 'Server Request', prefix: '[SR]', style: 'color: #f9a' },
            { type: 'Server Response', prefix: '[SRes]', style: 'color: #fd8' }
        ];
        
        // Sample API endpoints
        const endpoints = [
            '/api/users', 
            '/auth/user', 
            '/users/usr_123456',
            '/worlds/wrld_987654',
            '/auth/twofactorauth/totp/verify',
            '/users/friends',
            '/instances/join'
        ];
        
        // Generate log entries
        for (let i = 0; i < logCount; i++) {
            const logType = logTypes[Math.floor(Math.random() * logTypes.length)];
            const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
            const timestamp = new Date(Date.now() - Math.random() * 3600000).toISOString().replace('T', ' ').substr(0, 19);
            
            // Create different log line formats
            let logText = '';
            const randomFactor = Math.random();
            
            if (randomFactor < 0.3) {
                // Short log line
                logText = `${timestamp} ${logType.prefix} ${endpoint} - ${Math.floor(Math.random() * 1000)}ms`;
            } else if (randomFactor < 0.7) {
                // Medium log line
                logText = `${timestamp} ${logType.prefix} ${endpoint} - Status: ${Math.random() < 0.9 ? 200 : 404} - Response time: ${Math.floor(Math.random() * 1000)}ms`;
            } else {
                // Long log line with JSON
                const jsonObj = { 
                    status: Math.random() < 0.9 ? 'success' : 'error',
                    time: Math.floor(Math.random() * 1000),
                    data: {
                        id: `usr_${Math.floor(Math.random() * 1000000)}`,
                        displayName: `User${Math.floor(Math.random() * 100)}`,
                        status: ['online', 'offline', 'busy'][Math.floor(Math.random() * 3)],
                        location: Math.random() < 0.5 ? 'private' : `wrld_${Math.floor(Math.random() * 1000000)}`
                    }
                };
                logText = `${timestamp} ${logType.prefix} ${endpoint} - ${JSON.stringify(jsonObj, null, 2)}`;
            }
            
            // Create and append log entry
            const logEntry = document.createElement('div');
            logEntry.className = 'log-entry';
            logEntry.textContent = logText;
            logEntry.style = logType.style;
            logConsoleOutput.appendChild(logEntry);
        }
        
        // Scroll to bottom
        logConsoleOutput.scrollTop = logConsoleOutput.scrollHeight;
    }
    
    // --- Admin Functions ---
    function startUptimeCounter() {
        if (uptimeInterval) {
            clearInterval(uptimeInterval);
        }
        
        const updateUptime = () => {
            const now = new Date();
            const diff = now - startTime;
            
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
    
    // --- Initialize Everything ---
    function initialize() {
        // Set up tab handling
        setupTabHandling();
        
        // Set up admin button click handlers
        document.getElementById('shutdown-server-btn').addEventListener('click', () => {
            showShutdownConfirmation();
        });
        
        // Setup shutdown confirmation dialog
        setupShutdownDialog();
        
        document.getElementById('test-announce-btn').addEventListener('click', () => {
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
        });
        
        // Initialize section states
        initializeSectionStates();
        
        // Connect to WebSocket
        connectWebSocket();
    }
    
    // --- Shutdown Dialog Functions ---
    function setupShutdownDialog() {
        const dialog = document.getElementById('confirm-dialog');
        const confirmYesBtn = document.getElementById('confirm-yes');
        const confirmNoBtn = document.getElementById('confirm-no');
        
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
    
    function sendShutdownRequest() {
        if (websocket && websocket.readyState === WebSocket.OPEN) {
            log('info', 'Sending SHUTDOWN request to server...');
            statusMessage = 'Shutting down server...';
            renderStatusLine();
            
            // Send shutdown command via WebSocket
            websocket.send(JSON.stringify({
                type: 'COMMAND',
                command: 'SHUTDOWN'
            }));
            
            // Display a message to the user
            const shutdownMessage = document.createElement('div');
            shutdownMessage.className = 'shutdown-message';
            shutdownMessage.innerHTML = '<h3>Server Shutdown Initiated</h3>' + 
                '<p>The server is shutting down. This page will no longer be functional.</p>' +
                '<p>You may close this window.</p>';
            document.body.appendChild(shutdownMessage);
            
            // Disable controls
            document.getElementById('shutdown-server-btn').disabled = true;
            document.getElementById('refresh-button').disabled = true;
        } else {
            log('error', 'Cannot send shutdown request: WebSocket not connected');
            alert('Cannot send shutdown request: Not connected to server.');
        }
    }
    
    // Start initialization
    initialize();
}); 