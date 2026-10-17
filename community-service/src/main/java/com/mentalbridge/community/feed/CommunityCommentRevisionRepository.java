package com.mentalbridge.community.feed;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CommunityCommentRevisionRepository extends JpaRepository<CommunityCommentRevisionEntity, UUID> {
}
