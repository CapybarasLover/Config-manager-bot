package com.petr.db.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user")
@RequiredArgsConstructor
@Getter
@Setter
public class User {
    @Id
    private int tgChatId;

}
