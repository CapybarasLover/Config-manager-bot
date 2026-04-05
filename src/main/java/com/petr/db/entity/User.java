package com.petr.db.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "tg_user")
@Getter @Setter
@RequiredArgsConstructor
public class User {
    @ColumnDefault("'w'")
    @Column(nullable = false, name = "wait_accept", length = Integer.MAX_VALUE)
    private String waitAccept = "w"; // дефолтное значение при создании юзера
    @Column(name = "has_config", nullable = false)
    private Boolean hasConfig;
    @Column(name = "tg_name", length = Integer.MAX_VALUE)
    private String tgName;
    @Id
    @Column(name = "id", nullable = false)
    private Long id;
}
