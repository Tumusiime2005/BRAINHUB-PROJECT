package com.brainhub;

public record User(long id, String fullName, String email, Role role) {
    public enum Role {
        RESEARCHER,
        ADMIN
    }
}
