package com.mentalbridge.community.feed;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.community.feed.CommunityResponses.Topic;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
class CommunityTopicService {

	private static final int MAXIMUM_FILTER_TOPICS = 3;

	private final CommunityTopicRepository topics;

	CommunityTopicService(CommunityTopicRepository topics) {
		this.topics = topics;
	}

	@Transactional(readOnly = true)
	List<Topic> activeTopics() {
		return topics.findByActiveTrueOrderByDisplayOrderAsc().stream()
				.map(topic -> new Topic(topic.code(), topic.label(), topic.description()))
				.toList();
	}

	@Transactional(readOnly = true)
	List<CommunityTopic> validateFilter(List<CommunityTopic> requested) {
		if (requested == null || requested.isEmpty()) {
			return List.of();
		}
		var unique = new LinkedHashSet<>(requested);
		if (requested.size() > MAXIMUM_FILTER_TOPICS || unique.size() != requested.size() || unique.contains(null)) {
			throw CommunityApiException.invalidTopicFilter();
		}
		ensureActive(unique, Set.of());
		return List.copyOf(unique);
	}

	@Transactional(readOnly = true)
	void validatePostTopics(Set<CommunityTopic> requested, Set<CommunityTopic> historicalTopics) {
		ensureActive(requested, historicalTopics);
	}

	private void ensureActive(Set<CommunityTopic> requested, Set<CommunityTopic> allowedHistoricalTopics) {
		var active = new HashSet<>(topics.findActiveCodes(requested));
		var unavailable = requested.stream()
				.filter(topic -> !active.contains(topic) && !allowedHistoricalTopics.contains(topic))
				.findAny();
		if (unavailable.isPresent()) {
			throw CommunityApiException.topicUnavailable();
		}
	}
}
