package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import java.util.UUID;

@Entity
@Table(name = "menu_media")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "image_data", columnDefinition = "BYTEA")
    private byte[] imageData;

    private String contentType;
}
