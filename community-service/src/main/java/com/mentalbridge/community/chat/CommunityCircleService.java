package com.mentalbridge.community.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import com.mentalbridge.community.feed.CommunityMemberService;
import com.mentalbridge.community.feed.CommunityResponses.Author;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CommunityCircleService {
    private final JdbcTemplate db;
    private final ChatAccess access;
    private final ChatCrypto crypto;
    private final CommunityMemberService identities;
    private final CommunityChatProfiles profiles;
    private final ChatAccountAuthorizer accounts;
    private final FriendshipProperties friendships;
    private final ChatProperties properties;

    CommunityCircleService(JdbcTemplate db, ChatAccess access, ChatCrypto crypto, CommunityMemberService identities,
            CommunityChatProfiles profiles, ChatAccountAuthorizer accounts, FriendshipProperties friendships, ChatProperties properties) {
        this.db = db; this.access = access; this.crypto = crypto; this.identities = identities;
        this.profiles = profiles; this.accounts = accounts; this.friendships = friendships;
        this.properties = properties;
    }

    void authorize(UUID subject, Collection<UUID> invitees, UUID correlation) {
        friendships.requireEnabled();
        properties.requireEnabled();
        var subjects = new HashSet<UUID>(); subjects.add(subject);
        for (var id : invitees) subjects.add(profiles.byId(id).filter(p -> p.active()).orElseThrow(ChatException::target).subject());
        accounts.require(subjects, correlation);
    }

    @Transactional
    Page<Circle> list(UUID subject, UUID cursor, int limit) {
        var actor = access.actor(subject); bounded(limit);
        var ids = db.query("select c.id from community_circle c join community_circle_member m on m.circle_id=c.id where m.profile_id=? and m.state in ('ACTIVE','PENDING') and c.state='ACTIVE' and (?::uuid is null or c.id > ?::uuid) order by c.id limit ?",
                (rs, n) -> rs.getObject(1, UUID.class), actor.id(), cursor, cursor, limit + 1);
        var visible = ids.stream().limit(limit).filter(id -> ownerVisible(actor.id(), id)).map(id -> circle(actor.id(), id)).toList();
        return new Page<>(visible, ids.size() > limit ? ids.get(limit - 1) : null);
    }

    @Transactional
    Circle create(UUID subject, Create input, UUID key) {
        validate(input.name(), 80); if (input.description() == null || input.description().length() > 500 || input.invitees() == null || input.invitees().size() > 19 || new HashSet<>(input.invitees()).size() != input.invitees().size() || input.invitees().contains(null)) throw ChatException.invalid();
        var actor = access.actor(subject);
        String normalized = input.name().trim() + "\n" + input.description() + "\n" + input.invitees().stream().sorted().toList();
        var replay = db.queryForList("select id, request_fingerprint from community_circle where owner_profile_id=? and idempotency_key=?", actor.id(), key);
        if (!replay.isEmpty()) {
            if (!crypto.matches((String) replay.getFirst().get("request_fingerprint"), "circle-create", normalized)) throw ChatException.conflict();
            var id = (UUID) replay.getFirst().get("id"); require(actor.id(), id, true); return circle(actor.id(), id);
        }
        if (db.queryForObject("select count(*) from community_circle where owner_profile_id=? and created_at > now()-interval '1 day'", Long.class, actor.id()) >= 5) throw ChatException.limited();
        for (var target : input.invitees()) friend(actor.id(), target);
        var id = UUID.randomUUID();
        db.update("insert into community_circle(id,owner_profile_id,name_ciphertext,description_ciphertext,state,idempotency_key,request_fingerprint) values(?,?,?,?,'ACTIVE',?,?)",
                id, actor.id(), crypto.seal("circle-name:" + id, input.name().trim()), crypto.seal("circle-description:" + id, input.description()), key, crypto.fingerprint("circle-create", normalized));
        db.update("insert into community_circle_member(circle_id,profile_id,inviter_profile_id,role,state,joined_at) values(?,?,?,'OWNER','ACTIVE',now())", id, actor.id(), actor.id());
        for (var target : input.invitees()) insertInvite(id, actor.id(), target);
        return circle(actor.id(), id);
    }

    @Transactional
    Detail detail(UUID subject, UUID id) {
        var actor = access.actor(subject); require(actor.id(), id, false);
        var members = db.query("select profile_id,role,joined_at from community_circle_member where circle_id=? and state='ACTIVE' order by role desc,joined_at,profile_id", (rs, n) -> {
            var profile = identities.visibleProfile(actor.id(), rs.getObject("profile_id", UUID.class));
            return profile.map(p -> new Member(p, rsString(rs, "role"), rsInstant(rs, "joined_at"))).orElse(null);
        }, id).stream().filter(Objects::nonNull).toList();
        return new Detail(circle(actor.id(), id), members);
    }

    @Transactional
    void invite(UUID subject, UUID id, UUID target) {
        var actor = access.actor(subject); require(actor.id(), id, true); friend(actor.id(), target);
        var old = db.queryForList("select state from community_circle_member where circle_id=? and profile_id=?", id, target);
        if (!old.isEmpty()) {
            if (Set.of("PENDING", "ACTIVE").contains(old.getFirst().get("state"))) return;
            throw ChatException.notAllowed();
        }
        if (db.queryForObject("select count(*) from community_circle_member where circle_id=? and state in ('ACTIVE','PENDING')", Long.class, id) >= 50) throw ChatException.limited();
        insertInvite(id, actor.id(), target);
    }

    @Transactional
    void decide(UUID subject, UUID id, String decision) {
        if (!Set.of("ACCEPT", "DECLINE").contains(decision)) throw ChatException.invalid();
        var actor = access.actor(subject); lock(id);
        if (!ownerVisible(actor.id(), id)) throw ChatException.target();
        var rows = db.queryForList("select state,inviter_profile_id from community_circle_member where circle_id=? and profile_id=?", id, actor.id());
        if (rows.isEmpty()) throw ChatException.target();
        String state = decision.equals("ACCEPT") ? "ACTIVE" : "DECLINED";
        if (rows.getFirst().get("state").equals(state)) return;
        if (!rows.getFirst().get("state").equals("PENDING")) throw ChatException.notAllowed();
        friend(actor.id(), (UUID) rows.getFirst().get("inviter_profile_id"));
        db.update("update community_circle_member set state=?,joined_at=" + (state.equals("ACTIVE") ? "now()" : "null") + " where circle_id=? and profile_id=?", state, id, actor.id());
    }

    @Transactional
    void removeMember(UUID subject, UUID id, UUID target) {
        var actor = access.actor(subject); require(actor.id(), id, !actor.id().equals(target));
        if (Objects.equals(lock(id).get("owner_profile_id"), target)) throw ChatException.notAllowed();
        db.update("update community_circle_member set state='REMOVED', joined_at=null where circle_id=? and profile_id=?", id, target);
    }

    @Transactional
    void archive(UUID subject, UUID id) {
        var actor = access.actor(subject); require(actor.id(), id, true);
        db.update("update community_circle set state='ARCHIVED' where id=?", id);
    }

    @Transactional
    Page<Entry> entries(UUID subject, UUID id, UUID parent, UUID cursor, int limit) {
        var actor = access.actor(subject); require(actor.id(), id, false); bounded(limit);
        if (parent != null) root(actor.id(), id, parent);
        if (cursor != null && db.queryForObject("select count(*) from community_circle_entry where id=? and circle_id=? and parent_id is not distinct from ?::uuid", Long.class, cursor, id, parent) == 0) throw ChatException.invalid();
        var rows = db.queryForList("select * from community_circle_entry e where circle_id=? and parent_id is not distinct from ?::uuid and state='ACTIVE' and (?::uuid is null or (created_at,id) < (select created_at,id from community_circle_entry where id=?::uuid)) order by created_at desc,id desc limit ?", id, parent, cursor, cursor, limit + 1);
        var items = rows.stream().limit(limit).filter(row -> identities.visibleProfile(actor.id(), (UUID) row.get("author_profile_id")).isPresent()).map(row -> entry(actor.id(), id, row)).toList();
        return new Page<>(items, rows.size() > limit ? (UUID) rows.get(limit - 1).get("id") : null);
    }

    @Transactional
    Entry publish(UUID subject, UUID id, UUID parent, String text, UUID key) {
        validate(text, 5000); var actor = access.actor(subject); require(actor.id(), id, false);
        if (parent != null) root(actor.id(), id, parent);
        String normalized = id + ":" + parent + ":" + text.trim();
        var replay = db.queryForList("select * from community_circle_entry where author_profile_id=? and idempotency_key=?", actor.id(), key);
        if (!replay.isEmpty()) {
            var row = replay.getFirst();
            if (!crypto.matches((String) row.get("request_fingerprint"), "circle-entry", normalized)) throw ChatException.conflict();
            if (!row.get("state").equals("ACTIVE")) throw ChatException.notAllowed();
            return entry(actor.id(), id, row);
        }
        if (db.queryForObject("select count(*) from community_circle_entry where author_profile_id=? and created_at > now()-interval '1 minute'", Long.class, actor.id()) >= 10) throw ChatException.limited();
        var entryId = UUID.randomUUID();
        db.update("insert into community_circle_entry(id,circle_id,parent_id,author_profile_id,content_ciphertext,state,idempotency_key,request_fingerprint) values(?,?,?,?,?,'ACTIVE',?,?)", entryId, id, parent, actor.id(), crypto.seal("circle-entry:" + entryId, text.trim()), key, crypto.fingerprint("circle-entry", normalized));
        return entry(actor.id(), id, db.queryForMap("select * from community_circle_entry where id=?", entryId));
    }

    @Transactional
    void delete(UUID subject, UUID id, UUID entryId) {
        var actor = access.actor(subject); var membership = require(actor.id(), id, false);
        var rows = db.queryForList("select author_profile_id from community_circle_entry where circle_id=? and id=?", id, entryId);
        if (rows.isEmpty() || (!membership.get("role").equals("OWNER") && !rows.getFirst().get("author_profile_id").equals(actor.id()))) throw ChatException.target();
        db.update("update community_circle_entry set state='DELETED',content_ciphertext='' where circle_id=? and id=?", id, entryId);
    }

    private void friend(UUID actor, UUID target) {
        if (actor.equals(target) || identities.visibleProfile(actor, target).isEmpty() || !access.friends(actor, target)) throw ChatException.target();
    }
    private void insertInvite(UUID id, UUID actor, UUID target) {
        db.update("insert into community_circle_member(circle_id,profile_id,inviter_profile_id,role,state) values(?,?,?,'MEMBER','PENDING')", id, target, actor);
    }
    private Map<String,Object> lock(UUID id) {
        var rows = db.queryForList("select * from community_circle where id=? and state='ACTIVE' for update", id);
        if (rows.isEmpty()) throw ChatException.target(); return rows.getFirst();
    }
    private boolean ownerVisible(UUID actor, UUID id) {
        var rows = db.queryForList("select owner_profile_id from community_circle where id=?", id);
        return !rows.isEmpty() && identities.visibleProfile(actor, (UUID) rows.getFirst().get("owner_profile_id")).isPresent();
    }
    private Map<String,Object> require(UUID actor, UUID id, boolean owner) {
        lock(id);
        if (!ownerVisible(actor, id)) throw ChatException.target();
        var rows = db.queryForList("select role from community_circle_member where circle_id=? and profile_id=? and state='ACTIVE'", id, actor);
        if (rows.isEmpty() || (owner && !rows.getFirst().get("role").equals("OWNER"))) throw ChatException.target();
        return rows.getFirst();
    }
    private Circle circle(UUID actor, UUID id) {
        var row = db.queryForMap("select c.*,m.state membership,m.role,(select count(*) from community_circle_member n where n.circle_id=c.id and n.state='ACTIVE') member_count from community_circle c join community_circle_member m on m.circle_id=c.id where c.id=? and m.profile_id=?", id, actor);
        return new Circle(id, crypto.open("circle-name:" + id, (String) row.get("name_ciphertext")), crypto.open("circle-description:" + id, (String) row.get("description_ciphertext")), (String) row.get("membership"), (String) row.get("role"), ((Number) row.get("member_count")).intValue(), ((java.sql.Timestamp) row.get("created_at")).toInstant());
    }
    private Entry entry(UUID actor, UUID id, Map<String,Object> row) {
        var entryId = (UUID) row.get("id"); var author = (UUID) row.get("author_profile_id");
        boolean owner = db.queryForObject("select owner_profile_id=? from community_circle where id=?", Boolean.class, actor, id);
        return new Entry(entryId, identities.visibleProfile(actor, author).orElse(null), crypto.open("circle-entry:" + entryId, (String) row.get("content_ciphertext")), ((java.sql.Timestamp) row.get("created_at")).toInstant(), owner || actor.equals(author));
    }
    private void root(UUID actor, UUID id, UUID parent) {
        var rows = db.queryForList("select author_profile_id from community_circle_entry where circle_id=? and id=? and parent_id is null and state='ACTIVE'", id, parent);
        if (rows.isEmpty() || identities.visibleProfile(actor, (UUID) rows.getFirst().get("author_profile_id")).isEmpty()) throw ChatException.target();
    }
    private void bounded(int limit) { if (limit < 1 || limit > 10) throw ChatException.invalid(); }
    private void validate(String text, int max) { if (text == null || text.isBlank() || text.codePointCount(0,text.length()) > max) throw ChatException.invalid(); }
    private static String rsString(ResultSet rs, String key) { try { return rs.getString(key); } catch (SQLException e) { throw ChatException.unavailable(); } }
    private static Instant rsInstant(ResultSet rs, String key) { try { return rs.getTimestamp(key).toInstant(); } catch (SQLException e) { throw ChatException.unavailable(); } }
    record Create(String name, String description, List<UUID> invitees) { @Override public String toString() { return "CircleCreate[REDACTED]"; } }
    record Circle(UUID id, String name, String description, String membership, String role, int memberCount, Instant createdAt) { @Override public String toString() { return "Circle[REDACTED]"; } }
    record Member(Author profile, String role, Instant joinedAt) {}
    record Detail(Circle group, List<Member> members) {}
    record Entry(UUID id, Author author, String text, Instant createdAt, boolean canDelete) { @Override public String toString() { return "CircleEntry[REDACTED]"; } }
    record Page<T>(List<T> items, UUID nextCursor) {}
}
