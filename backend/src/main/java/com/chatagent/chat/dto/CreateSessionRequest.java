package com.chatagent.chat.dto;

import lombok.Data;

@Data
public class CreateSessionRequest {
    private String title;
    private String model;
}
