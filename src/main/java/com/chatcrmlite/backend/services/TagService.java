package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Tag;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TagService {

    @Autowired
    private TagRepository tagRepository;

    /**
     * Synchronizes a list of string tag names for a specific entity type and owner.
     * Returns a list of Tag entities (existing or newly created).
     */
    @Transactional
    public List<Tag> getOrCreateTags(List<String> tagNames, String entityType, User owner) {
        if (tagNames == null || tagNames.isEmpty()) {
            return new ArrayList<>();
        }

        return tagNames.stream()
                .filter(name -> name != null && !name.trim().isEmpty())
                .map(name -> tagRepository.findByOwnerAndEntityTypeAndName(owner, entityType, name.trim())
                        .orElseGet(() -> tagRepository.save(new Tag(owner, entityType, name.trim()))))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<String> getAllContactTags(User owner) {
        return tagRepository.findAllByOwnerAndEntityType(owner, Tag.TYPE_CONTACT)
                .stream()
                .map(Tag::getName)
                .collect(Collectors.toList());
    }
}
