package com.mentalbridge.consultation.discovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.appointment.AppointmentModality;
import com.mentalbridge.consultation.availability.AvailabilityProperties;
import com.mentalbridge.consultation.discovery.DiscoveryResponse.BookingHandoff;
import com.mentalbridge.consultation.discovery.DiscoveryResponse.Compatibility;
import com.mentalbridge.consultation.discovery.DiscoveryResponse.ContextState;
import com.mentalbridge.consultation.discovery.DiscoveryResponse.TimezoneMatch;
import com.mentalbridge.consultation.entitlement.CurrentServiceEntitlementService;
import com.mentalbridge.consultation.entitlement.ServicePackage;
import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.specialist.SupportArea;

@Service
public class DiscoveryService {

	static final String POLICY_VERSION = "specialist-discovery-v1";
	private static final Duration MINIMUM_LEAD_TIME = Duration.ofHours(4);
	private static final Duration MAXIMUM_WINDOW = Duration.ofDays(90);
	private static final int MAXIMUM_SLOTS_PER_SPECIALIST = 20;
	private static final Set<String> LANGUAGES = Set.of("vi", "en");

	private final JdbcClient jdbc;
	private final ScreeningContextResolver screeningContexts;
	private final CurrentServiceEntitlementService entitlements;
	private final AvailabilityProperties availability;
	private final ObjectMapper json;
	private final Clock clock;

	public DiscoveryService(JdbcClient jdbc, ScreeningContextResolver screeningContexts,
			CurrentServiceEntitlementService entitlements, AvailabilityProperties availability,
			ObjectMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.screeningContexts = screeningContexts;
		this.entitlements = entitlements;
		this.availability = availability;
		this.json = json;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public DiscoveryResponse.Page discover(UUID userId, String bearerToken, Criteria suppliedCriteria,
			int limit, String encodedCursor) {
		var now = clock.instant();
		var criteria = validate(suppliedCriteria, now);
		var context = context(criteria.supportEvaluationId(), bearerToken);
		var entitlement = entitlements.current(userId);
		var ranked = rank(load(criteria, null, now), criteria, context, now);
		var criteriaHash = criteriaHash(suppliedCriteria);
		var cursor = encodedCursor == null ? null : decodeCursor(encodedCursor, criteriaHash);
		if (cursor != null) ranked = ranked.stream()
				.filter(candidate -> keyComparator().compare(candidate.key(), cursor.key()) > 0).toList();
		var page = ranked.stream().limit(limit).toList();
		var nextCursor = ranked.size() > limit ? encodeCursor(criteriaHash, page.get(page.size() - 1).key()) : null;
		var items = page.stream().map(RankedProfile::item).toList();
		return new DiscoveryResponse.Page(items, items.size(), nextCursor, POLICY_VERSION, now, context.state(),
				entitlement.packageCode(), handoff(entitlement.packageCode()), availability.videoEnabled());
	}

	@Transactional(readOnly = true)
	public DiscoveryResponse.Item detail(UUID userId, String bearerToken, UUID specialistAccountId,
			Criteria suppliedCriteria) {
		var now = clock.instant();
		var criteria = validate(suppliedCriteria, now);
		var context = context(criteria.supportEvaluationId(), bearerToken);
		entitlements.current(userId);
		return rank(load(criteria, specialistAccountId, now), criteria, context, now).stream().findFirst()
				.map(RankedProfile::item)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SPECIALIST_NOT_DISCOVERABLE",
						"The specialist is not currently discoverable"));
	}

	private Criteria validate(Criteria criteria, Instant now) {
		var language = criteria.language() == null ? null : criteria.language().strip().toLowerCase(Locale.ROOT);
		if (language != null && !LANGUAGES.contains(language)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "language is not supported");
		}
		String timezone = criteria.timezone() == null ? null : criteria.timezone().strip();
		if (timezone != null) {
			try { ZoneId.of(timezone); }
			catch (ZoneRulesException exception) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "timezone must be an IANA timezone");
			}
		}
		var minimum = now.plus(MINIMUM_LEAD_TIME);
		var from = criteria.from() == null || criteria.from().isBefore(minimum) ? minimum : criteria.from();
		var to = criteria.to() == null ? now.plus(MAXIMUM_WINDOW) : criteria.to();
		if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DISCOVERY_RANGE",
					"Discovery range must be positive and no longer than 90 days");
		}
		return new Criteria(criteria.supportEvaluationId(), criteria.supportArea(), language, timezone,
				criteria.modality(), from, to);
	}

	private Context context(UUID supportEvaluationId, String bearerToken) {
		if (supportEvaluationId == null) return new Context(ContextState.NOT_REQUESTED, null);
		return screeningContexts.resolve(supportEvaluationId, bearerToken)
				.<Context>map(value -> new Context(ContextState.APPLIED, value))
				.orElseGet(() -> new Context(ContextState.UNAVAILABLE, null));
	}

	private List<Profile> load(Criteria criteria, UUID specialistAccountId, Instant now) {
		var rows = jdbc.sql("""
				select p.account_id, p.display_name, p.biography, p.years_experience, p.timezone,
				 (select string_agg(sa.support_area, ',' order by sa.support_area)
				  from specialist_profile_support_area sa where sa.specialist_account_id=p.account_id) support_areas,
				 (select string_agg(pl.language_tag, ',' order by pl.language_tag)
				  from specialist_profile_language pl where pl.specialist_account_id=p.account_id) languages,
				 slot.id slot_id, slot.start_at, slot.end_at, slot.timezone slot_timezone,
				 slot.modality, slot.version slot_version
				from specialist_profile p
				left join lateral (
				 select s.id, s.start_at, s.end_at, s.timezone, s.modality, s.version
				 from availability_slot s
				 where s.specialist_account_id=p.account_id and s.status='ACTIVE'
				 and s.start_at>=:from and s.start_at<:to
				 and (:modalityFiltered=false or s.modality=:modality)
				 and (s.modality<>'IN_APP_VIDEO' or :videoEnabled)
				 and not exists (
				  select 1 from appointment a where a.availability_slot_id=s.id
				  and a.status in ('REQUESTED','CONFIRMED','IN_PROGRESS')
				 )
				 order by s.start_at, s.id limit :slotLimit
				) slot on true
				where p.approval_status='APPROVED'
				and (:specialistFiltered=false or p.account_id=:specialistAccountId)
				and (:supportAreaFiltered=false or exists (
				 select 1 from specialist_profile_support_area filter_area
				 where filter_area.specialist_account_id=p.account_id and filter_area.support_area=:supportArea
				))
				order by p.account_id, slot.start_at, slot.id
				""").param("from", database(criteria.from())).param("to", database(criteria.to()))
				.param("modalityFiltered", criteria.modality() != null)
				.param("modality", criteria.modality() == null ? AppointmentModality.IN_APP_CHAT.name() : criteria.modality().name())
				.param("videoEnabled", availability.videoEnabled()).param("slotLimit", MAXIMUM_SLOTS_PER_SPECIALIST)
				.param("specialistFiltered", specialistAccountId != null)
				.param("specialistAccountId", specialistAccountId == null ? new UUID(0, 0) : specialistAccountId)
				.param("supportAreaFiltered", criteria.supportArea() != null)
				.param("supportArea", criteria.supportArea() == null ? SupportArea.DEPRESSIVE_SYMPTOMS.name()
						: criteria.supportArea().name())
				.query((row, ignored) -> new DiscoveryRow(row.getObject("account_id", UUID.class),
						row.getString("display_name"), row.getString("biography"), row.getInt("years_experience"),
						row.getString("timezone"), row.getString("support_areas"), row.getString("languages"),
						row.getObject("slot_id", UUID.class), instant(row, "start_at"), instant(row, "end_at"),
						row.getString("slot_timezone"), row.getString("modality"), row.getObject("slot_version", Long.class)))
				.list();
		var profiles = new LinkedHashMap<UUID, ProfileBuilder>();
		for (var row : rows) {
			var profile = profiles.computeIfAbsent(row.accountId(), ignored -> new ProfileBuilder(row));
			if (row.slotId() != null) profile.slots.add(new DiscoveryResponse.Slot(row.slotId(), row.accountId(),
					row.startAt(), row.endAt(), row.slotTimezone(), AppointmentModality.valueOf(row.modality()),
					row.slotVersion()));
		}
		return profiles.values().stream().map(ProfileBuilder::build).toList();
	}

	private List<RankedProfile> rank(List<Profile> profiles, Criteria criteria, Context context, Instant generatedAt) {
		return profiles.stream().map(profile -> ranked(profile, criteria, context, generatedAt))
				.sorted(comparator()).toList();
	}

	private RankedProfile ranked(Profile profile, Criteria criteria, Context context, Instant generatedAt) {
		int compatibilityRank = compatibilityRank(profile, context);
		var compatibility = switch (context.state()) {
			case NOT_REQUESTED -> Compatibility.NEUTRAL;
			case UNAVAILABLE -> Compatibility.UNAVAILABLE;
			case APPLIED -> compatibilityRank > 0 ? Compatibility.MATCHED : Compatibility.NOT_MATCHED;
		};
		Boolean languageMatched = criteria.language() == null ? null : profile.languages().contains(criteria.language());
		var earliest = profile.slots().isEmpty() ? null : profile.slots().getFirst().startAt();
		TimezoneMatch timezoneMatch;
		Integer timezoneDistance;
		if (criteria.timezone() == null) {
			timezoneMatch = TimezoneMatch.NOT_REQUESTED;
			timezoneDistance = null;
		}
		else if (criteria.timezone().equals(profile.timezone())) {
			timezoneMatch = TimezoneMatch.EXACT;
			timezoneDistance = 0;
		}
		else {
			timezoneMatch = TimezoneMatch.OFFSET_DISTANCE;
			timezoneDistance = offsetDistance(criteria.timezone(), profile.timezone(), generatedAt);
		}
		var codes = List.of(compatibilityCode(compatibility), languageCode(languageMatched),
				profile.slots().isEmpty() ? "NO_SELECTABLE_SLOT" : "SELECTABLE_SLOT_AVAILABLE",
				timezoneCode(timezoneMatch), "RATING_NOT_AVAILABLE");
		var explanation = new DiscoveryResponse.Explanation(compatibility, languageMatched, !profile.slots().isEmpty(),
				earliest, timezoneMatch, timezoneDistance, false, codes);
		var item = new DiscoveryResponse.Item(profile.accountId(), profile.displayName(), profile.bio(),
				profile.supportAreas(), profile.languages(), profile.yearsOfExperience(), profile.timezone(), explanation,
				profile.slots());
		var key = new SortKey(compatibilityRank, Boolean.TRUE.equals(languageMatched) ? 1 : 0,
				profile.slots().isEmpty() ? 0 : 1, earliest, timezoneMatch == TimezoneMatch.EXACT ? 1 : 0,
				timezoneDistance == null ? 0 : timezoneDistance, profile.accountId());
		return new RankedProfile(item, key);
	}

	private int compatibilityRank(Profile profile, Context context) {
		if (context.value() == null) return 0;
		return profile.supportAreas().stream().mapToInt(area -> context.value().priorities().getOrDefault(area, 0)).max()
				.orElse(0);
	}

	private Comparator<RankedProfile> comparator() {
		return (left, right) -> keyComparator().compare(left.key(), right.key());
	}

	private Comparator<SortKey> keyComparator() {
		return Comparator.comparingInt(SortKey::compatibilityRank).reversed()
				.thenComparing(Comparator.comparingInt(SortKey::languageRank).reversed())
				.thenComparing(Comparator.comparingInt(SortKey::availabilityRank).reversed())
				.thenComparing(SortKey::earliestSlot, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(Comparator.comparingInt(SortKey::exactTimezoneRank).reversed())
				.thenComparingInt(SortKey::timezoneDistanceMinutes)
				.thenComparing(SortKey::specialistAccountId);
	}

	private String encodeCursor(String criteriaHash, SortKey key) {
		try {
			var bytes = json.writeValueAsBytes(new Cursor(POLICY_VERSION, criteriaHash, key));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		}
		catch (Exception exception) { throw new IllegalStateException("Discovery cursor could not be encoded", exception); }
	}

	private Cursor decodeCursor(String encoded, String criteriaHash) {
		try {
			var cursor = json.readValue(Base64.getUrlDecoder().decode(encoded), Cursor.class);
			if (!POLICY_VERSION.equals(cursor.policyVersion()) || !criteriaHash.equals(cursor.criteriaHash())
					|| cursor.key() == null || cursor.key().specialistAccountId() == null) throw new IllegalArgumentException();
			return cursor;
		}
		catch (Exception exception) {
			throw new ApiException(HttpStatus.CONFLICT, "DISCOVERY_CURSOR_STALE",
					"The discovery cursor does not match the current ranking request");
		}
	}

	private String criteriaHash(Criteria criteria) {
		try {
			var canonical = String.join("\n", value(criteria.supportEvaluationId()), value(criteria.supportArea()),
					value(normalizedLanguage(criteria.language())), value(normalizedTimezone(criteria.timezone())),
					value(criteria.modality()),
					value(criteria.from()), value(criteria.to()));
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
	}
	private String normalizedLanguage(String language) {
		return language == null ? null : language.strip().toLowerCase(Locale.ROOT);
	}
	private String normalizedTimezone(String timezone) {
		return timezone == null ? null : timezone.strip();
	}

	private String value(Object value) { return value == null ? "-" : value.toString(); }
	private BookingHandoff handoff(ServicePackage packageCode) {
		return packageCode == ServicePackage.FREE ? BookingHandoff.BROWSE_ONLY
				: BookingHandoff.BOOKING_POLICY_CHECK_REQUIRED;
	}
	private int offsetDistance(String requested, String specialist, Instant at) {
		var requestedOffset = ZoneId.of(requested).getRules().getOffset(at).getTotalSeconds();
		var specialistOffset = ZoneId.of(specialist).getRules().getOffset(at).getTotalSeconds();
		return Math.abs(requestedOffset - specialistOffset) / 60;
	}
	private String compatibilityCode(Compatibility value) {
		return switch (value) {
			case MATCHED -> "SCREENED_SUPPORT_AREA_MATCH";
			case NOT_MATCHED -> "NO_SCREENED_SUPPORT_AREA_MATCH";
			case NEUTRAL -> "NO_SCREENING_CONTEXT";
			case UNAVAILABLE -> "SCREENING_CONTEXT_UNAVAILABLE";
		};
	}
	private String languageCode(Boolean matched) {
		if (matched == null) return "NO_REQUESTED_LANGUAGE";
		return matched ? "REQUESTED_LANGUAGE_MATCH" : "REQUESTED_LANGUAGE_NOT_MATCHED";
	}
	private String timezoneCode(TimezoneMatch value) {
		return switch (value) {
			case NOT_REQUESTED -> "NO_REQUESTED_TIMEZONE";
			case EXACT -> "EXACT_TIMEZONE_MATCH";
			case OFFSET_DISTANCE -> "TIMEZONE_OFFSET_DISTANCE";
		};
	}
	private OffsetDateTime database(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
	private Instant instant(java.sql.ResultSet row, String name) throws java.sql.SQLException {
		var timestamp = row.getTimestamp(name);
		return timestamp == null ? null : timestamp.toInstant();
	}
	private Set<SupportArea> supportAreas(String values) {
		var result = new LinkedHashSet<SupportArea>();
		for (var value : values.split(",")) result.add(SupportArea.valueOf(value));
		return Set.copyOf(result);
	}
	private Set<String> languages(String values) {
		return Set.copyOf(List.of(values.split(",")));
	}

	public record Criteria(UUID supportEvaluationId, SupportArea supportArea, String language, String timezone,
			AppointmentModality modality, Instant from, Instant to) { }
	private record Context(ContextState state, ScreeningContextResolver.ScreeningContext value) { }
	private record Profile(UUID accountId, String displayName, String bio, int yearsOfExperience, String timezone,
			Set<SupportArea> supportAreas, Set<String> languages, List<DiscoveryResponse.Slot> slots) { }
	private record DiscoveryRow(UUID accountId, String displayName, String bio, int yearsOfExperience, String timezone,
			String supportAreas, String languages, UUID slotId, Instant startAt, Instant endAt, String slotTimezone,
			String modality, Long slotVersion) { }
	private record RankedProfile(DiscoveryResponse.Item item, SortKey key) { }
	private record SortKey(int compatibilityRank, int languageRank, int availabilityRank, Instant earliestSlot,
			int exactTimezoneRank, int timezoneDistanceMinutes, UUID specialistAccountId) { }
	private record Cursor(String policyVersion, String criteriaHash, SortKey key) { }

	private final class ProfileBuilder {
		private final DiscoveryRow source;
		private final List<DiscoveryResponse.Slot> slots = new ArrayList<>();
		private ProfileBuilder(DiscoveryRow source) { this.source = source; }
		private Profile build() {
			return new Profile(source.accountId(), source.displayName(), source.bio(), source.yearsOfExperience(),
					source.timezone(), supportAreas(source.supportAreas()), languages(source.languages()), List.copyOf(slots));
		}
	}
}
