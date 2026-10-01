package com.mentalbridge.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import com.cloudinary.Cloudinary;
import com.mentalbridge.community.configuration.CloudinaryProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityServiceApplicationTests extends CommunityTestProperties {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private Cloudinary cloudinary;

	@Autowired
	private CloudinaryProperties cloudinaryProperties;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Test
	void contextLoadsWithTypedCloudinaryConfiguration() {
		assertThat(cloudinary).isNotNull();
		assertThat(cloudinaryProperties.cloudName()).isEqualTo("test-cloud");
	}

	@Test
	void communityMigrationsApplyTheFeedAndPostLifecycleModel() {
		var changeSets = jdbc.sql("select id from databasechangelog order by orderexecuted")
				.query(String.class).list();
		var businessTables = jdbc.sql("""
				select table_name
				from information_schema.tables
				where table_schema = 'public'
				  and table_name not in ('databasechangelog', 'databasechangeloglock')
				order by table_name
				""").query(String.class).list();

		assertThat(changeSets).containsExactly("0001-community-foundation", "0002-community-feed",
				"0003-community-post-lifecycle", "0004-community-display-identity",
				"0005-community-media-lifecycle", "0006-community-request-fingerprint-varchar");
		assertThat(businessTables).containsExactly("community_block", "community_media", "community_post",
				"community_post_topic", "community_profile");
	}

	@Test
	void requestFingerprintsUseTheJpaCompatibleVarcharType() {
		var fingerprintColumns = jdbc.sql("""
				select table_name, data_type, character_maximum_length
				from information_schema.columns
				where table_schema = 'public'
				  and table_name in ('community_post', 'community_media')
				  and column_name = 'request_fingerprint'
				order by table_name
				""").query((resultSet, rowNumber) -> List.of(
					resultSet.getString("table_name"),
					resultSet.getString("data_type"),
					resultSet.getString("character_maximum_length")))
				.list();

		assertThat(fingerprintColumns).containsExactly(
				List.of("community_media", "character varying", "64"),
				List.of("community_post", "character varying", "64"));
	}

	@Test
	void healthEndpointIsPublicAndReportsUp() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void unknownApplicationRoutesRequireAuthentication() throws Exception {
		mvc.perform(get("/api/v1/community/not-implemented"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void identityJwtIsVerifiedLocallyWithIssuerAndAudience() {
		var token = signedToken(List.of("mentalbridge-test-api"));

		var decoded = jwtDecoder.decode(token);

		assertThat(decoded.getSubject()).isEqualTo("00000000-0000-0000-0000-000000000001");
		assertThat(decoded.getClaimAsStringList("roles")).containsExactly("USER");
	}

	@Test
	void identityJwtWithWrongAudienceIsRejected() {
		var token = signedToken(List.of("another-service"));

		assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtValidationException.class);
	}

	private String signedToken(List<String> audience) {
		var keyPair = CommunityTestProperties.jwtKeyPair();
		var jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
				.privateKey((RSAPrivateKey) keyPair.getPrivate()).keyID("community-test-key").build();
		var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
		var now = Instant.now();
		var claims = JwtClaimsSet.builder()
				.issuer("https://identity.test.mentalbridge")
				.subject("00000000-0000-0000-0000-000000000001")
				.audience(audience)
				.issuedAt(now)
				.expiresAt(now.plusSeconds(60))
				.claim("roles", List.of("USER"))
				.build();
		var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("community-test-key").build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
