package com.javaee.docmanager.security;

public class UserContext {

    private static final ThreadLocal<UserInfo> CURRENT_USER = new ThreadLocal<>();

    public static void setCurrentUser(Long userId, String username, String role) {
        CURRENT_USER.set(new UserInfo(userId, username, role));
    }

    public static UserInfo getCurrentUser() {
        return CURRENT_USER.get();
    }

    public static Long getCurrentUserId() {
        UserInfo info = CURRENT_USER.get();
        return info != null ? info.getUserId() : null;
    }

    public static String getCurrentUsername() {
        UserInfo info = CURRENT_USER.get();
        return info != null ? info.getUsername() : null;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }

    public static class UserInfo {
        private final Long userId;
        private final String username;
        private final String role;

        public UserInfo(Long userId, String username, String role) {
            this.userId = userId;
            this.username = username;
            this.role = role;
        }

        public Long getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getRole() { return role; }
    }
}
