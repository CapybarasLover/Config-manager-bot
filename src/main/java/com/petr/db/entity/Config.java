package com.petr.db.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "config")
@Getter @Setter
@RequiredArgsConstructor
public class Config {
    @Column(name = "sub_link", nullable = false, length = Integer.MAX_VALUE)
    private String subLink;

    @Column(name = "vless_link", nullable = false, length = Integer.MAX_VALUE)
    private String vlessLink;

    @Column(name = "config_name", nullable = false, length = Integer.MAX_VALUE)
    private String configName;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tg_id", nullable = false)
    private User tgUser;

    @Id
    @Column(name = "tg_id", nullable = false)
    private Long id;
}
