package com.example.vrcmonitor.services;

import com.example.vrcmonitor.config.UserConfig;
import com.example.vrcmonitor.models.VRChatUser;
import com.example.vrcmonitor.models.dto.StatusUpdateDTO;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class UserStateService {

    private static final Logger log = LoggerFactory.getLogger(UserStateService.class);
    private static final int MAX_HISTORY_PER_USER = 100; // Limit history size

    // Store latest state AND history per user ID
    private final Map<String, UserStateContainer> userStateAndHistory = new ConcurrentHashMap<>();

    public void updateUserState(String vrcUid, VRChatUser user, Instant timestamp) {
        UserState newState = new UserState(user, StatusType.OK, null, timestamp);
        UserStateContainer container = userStateAndHistory.computeIfAbsent(vrcUid, k -> new UserStateContainer());
        
        UserState previousState = container.getLatestState();
        boolean changed = previousState == null || 
                        previousState.statusType() != StatusType.OK || // Changed if previous was error
                        // Check state change (handle null user objects)
                        !nullSafeEquals(previousState.user() == null ? null : previousState.user().getState(), 
                                         user == null ? null : user.getState()) || 
                        // Check status change (handle null user objects)
                        !nullSafeEquals(previousState.user() == null ? null : previousState.user().getStatus(), 
                                         user == null ? null : user.getStatus());

        if (changed) 
        {
             container.addHistory(newState);
             // Use null checks for display name as well
             log.info("Updated state for {} ({}): State='{}', Status='{}' (History updated)", 
                      user != null ? user.getDisplayName() : "UNKNOWN_USER", 
                      vrcUid, 
                      user != null ? user.getState() : "N/A", 
                      user != null ? user.getStatus() : "N/A");
        } else {
             // Update timestamp even if state/status is the same
             container.setLatestState(newState);
             log.debug("Refreshed state for {} ({}) - no change: State='{}', Status='{}'", 
                      user != null ? user.getDisplayName() : "UNKNOWN_USER", 
                      vrcUid, 
                      user != null ? user.getState() : "N/A", 
                      user != null ? user.getStatus() : "N/A");
        }
    }

    public void updateUserErrorState(String vrcUid, String errorMessage, Instant timestamp) {
        UserStateContainer container = userStateAndHistory.computeIfAbsent(vrcUid, k -> new UserStateContainer());
        UserState previousState = container.getLatestState();
        VRChatUser lastKnownUser = (previousState != null && previousState.statusType() != StatusType.ERROR) ? previousState.user() : null;
        UserState errorState = new UserState(lastKnownUser, StatusType.ERROR, errorMessage, timestamp);
        
        // Only add error to history if previous state was OK
        if (previousState == null || previousState.statusType() == StatusType.OK) {
             container.addHistory(errorState);
             log.warn("Error updating state for {}: {} (History updated)", vrcUid, errorMessage);
        } else {
             // Update timestamp of existing error state
              container.setLatestState(errorState);
              log.warn("Refreshed error state for {}: {}", vrcUid, errorMessage);
        }
    }

    public UserState getLatestUserState(String vrcUid) {
        UserStateContainer container = userStateAndHistory.get(vrcUid);
        return (container != null) ? container.getLatestState() : null;
    }

    // Method to get snapshot for initial WebSocket send
    public Map<String, UserStateContainerSnapshot> getSnapshot() {
        Map<String, UserStateContainerSnapshot> snapshot = new ConcurrentHashMap<>();
        userStateAndHistory.forEach((uid, container) -> {
            snapshot.put(uid, container.getSnapshot());
        });
        return snapshot;
    }

    // Renamed from getAllUserStates
    public Map<String, UserState> getCurrentStates() {
        Map<String, UserState> current = new ConcurrentHashMap<>();
         userStateAndHistory.forEach((uid, container) -> {
             if (container.getLatestState() != null) {
                 current.put(uid, container.getLatestState());
             }
        });
        return current;
    }

    // Helper for null-safe equals
    private boolean nullSafeEquals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    // Container to hold both latest state and history
    @Getter // Lombok for getters
    private static class UserStateContainer {
        private UserState latestState;
        private final List<UserState> history = new CopyOnWriteArrayList<>(); // Thread-safe list

        public void addHistory(UserState state) {
            this.latestState = state;
            this.history.add(state);
            // Trim history if it exceeds the limit
            while (history.size() > MAX_HISTORY_PER_USER) {
                history.remove(0); // Remove the oldest entry
            }
        }
        
        public void setLatestState(UserState state) {
            this.latestState = state;
             // Update the timestamp of the last entry if it represents the same state logically
             if (!history.isEmpty()) {
                  UserState lastHistory = history.get(history.size() - 1);
                  if (lastHistory.statusType() == state.statusType() && 
                      nullSafeEquals(lastHistory.errorMessage(), state.errorMessage()) &&
                      (state.user() != null && lastHistory.user() != null && 
                       nullSafeEquals(lastHistory.user().getState(), state.user().getState()) &&
                       nullSafeEquals(lastHistory.user().getStatus(), state.user().getStatus())) ||
                      (state.user() == null && lastHistory.user() == null) // Both null (e.g., initial error state)
                     ) 
                  { 
                       // Replace last entry with updated timestamp rather than adding duplicate
                       history.set(history.size() - 1, state);
                       return;
                  } 
             }
             // If different or history empty, just add
             // This case should ideally be covered by addHistory, but added as safeguard
             // log.warn("setLatestState called without adding history when state changed, adding now.");
             // addHistory(state);
        }

        public UserStateContainerSnapshot getSnapshot() {
            return new UserStateContainerSnapshot(latestState, List.copyOf(history));
        }
        
        // Helper for null-safe equals within the container context
        private boolean nullSafeEquals(Object a, Object b) {
            return (a == b) || (a != null && a.equals(b));
        }
    }
    
    // Immutable snapshot DTO for sending over WebSocket
    public record UserStateContainerSnapshot(
        UserState latestState,
        List<UserState> history
    ) {}

    // Inner record for individual state points (remains same)
    public record UserState(
        VRChatUser user, 
        StatusType statusType,
        String errorMessage, 
        Instant lastUpdated
    ) {}

    // Enum for status type (remains same)
    public enum StatusType {
        OK, 
        ERROR
    }
} 