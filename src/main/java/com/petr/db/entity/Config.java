package com.petr.db.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Config {
    @Id
    private int tgChatId;

}
