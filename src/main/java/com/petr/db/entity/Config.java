package com.petr.db.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "config",
        uniqueConstraints = {
                @UniqueConstraint(name = "config_tg_id_country_unique", columnNames = {"tg_id", "country"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Config {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tg_id", nullable = false, foreignKey = @ForeignKey(name = "fk_tg_id"))
    private User tgUser;

    @Column(name = "config_name", nullable = false)
    private String configName;

    @Column(name = "vless_link")
    private String vlessLink;

    @Column(name = "sub_link", nullable = false)
    private String subLink;

    @Column(name = "xhttp_link")
    private String xhttpLink;

    @Column(name = "reality_link")
    private String realityLink;

    @Column(name = "country", nullable = false, length = 10)
    private String country = "latv";

    @Column(name = "status", nullable = false, length = 5)
    private String status = "w";
}