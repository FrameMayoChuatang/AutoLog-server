package io.autolog;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthApiTest extends ApiTestSupport {
    @Test void signupLoginLogoutAndPasswordHash() throws Exception {
        var login=call("POST","/api/auth/login",null,Map.of("email","OWNER@example.test","password",PASSWORD),200);
        String token=login.path("accessToken").asText();assertThat(token).hasSize(43);
        assertThat(login.path("admin").has("passwordHash")).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM admins WHERE email='owner@example.test'",String.class)).startsWith("$2a$").isNotEqualTo(PASSWORD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credentials WHERE token_hash=?",Integer.class,token)).isZero();
        call("POST","/api/auth/logout",token,null,204);call("GET","/api/auth/me",token,null,401);
    }
    @Test void staffSetupOneUseAndOwnerOnlyPermissions() throws Exception {
        var invitation=call("POST","/api/admins",owner,Map.of("email","staff@example.test","name","직원"),201);
        String setup=invitation.path("invitation").path("setupToken").asText();
        assertThat(invitation.path("invitation").path("setupLink").asText()).contains("/password/setup#token=");
        call("POST","/api/auth/login",null,Map.of("email","staff@example.test","password",PASSWORD),401);
        var session=call("POST","/api/auth/password/setup",null,Map.of("token",setup,"password",PASSWORD),200);
        String staff=session.path("accessToken").asText();
        call("GET","/api/vehicles",staff,null,200);call("GET","/api/admins",staff,null,403);
        call("POST","/api/admins",staff,Map.of("email","another@example.test","name","직원2"),403);
        call("POST","/api/auth/password/setup",null,Map.of("token",setup,"password",PASSWORD),401);
        call("PATCH","/api/admins/"+invitation.path("admin").path("id").asText()+"/active",owner,Map.of("active",false),200);
        call("GET","/api/vehicles",staff,null,401);
        call("POST","/api/auth/login",null,Map.of("email","staff@example.test","password",PASSWORD),401);
    }
    @Test void reissuedSetupRevokesOldLinkAndResetsSessions() throws Exception {
        var invited=call("POST","/api/admins",owner,Map.of("email","staff@example.test","name","직원"),201);
        String id=invited.path("admin").path("id").asText(),old=invited.path("invitation").path("setupToken").asText();
        var replacement=call("POST","/api/admins/"+id+"/setup-link",owner,null,200);
        call("POST","/api/auth/password/setup",null,Map.of("token",old,"password",PASSWORD),401);
        var session=call("POST","/api/auth/password/setup",null,Map.of("token",replacement.path("setupToken").asText(),"password",PASSWORD),200);
        var next=call("POST","/api/admins/"+id+"/setup-link",owner,null,200);
        call("POST","/api/auth/password/setup",null,Map.of("token",next.path("setupToken").asText(),"password",PASSWORD+"x"),200);
        call("GET","/api/auth/me",session.path("accessToken").asText(),null,401);
    }
    @Test void expiresCredentialsAndProtectsOwner() throws Exception {
        String ownerId=call("GET","/api/auth/me",owner,null,200).path("id").asText();
        call("PATCH","/api/admins/"+ownerId+"/active",owner,Map.of("active",false),403);
        var invited=call("POST","/api/admins",owner,Map.of("email","staff@example.test","name","직원"),201);
        jdbc.update("UPDATE credentials SET expires_at=now()-interval '1 second' WHERE kind='SETUP'");
        call("POST","/api/auth/password/setup",null,Map.of("token",invited.path("invitation").path("setupToken").asText(),"password",PASSWORD),401);
        jdbc.update("UPDATE credentials SET expires_at=now()-interval '1 second' WHERE kind='ADMIN'");
        call("GET","/api/vehicles",owner,null,401);
    }
    @Test void companyBoundariesApplyToAdminAndVehicle() throws Exception {
        String outsider=signup("outsider@example.test").path("accessToken").asText();
        assertThat(call("GET","/api/vehicles",outsider,null,200)).isEmpty();
        call("GET","/api/vehicles/"+vehicle,outsider,null,404);
        call("POST","/api/vehicles/"+vehicle+"/pairing",outsider,null,404);
        var id=call("GET","/api/auth/me",owner,null,200).path("id").asText();
        call("PATCH","/api/admins/"+id+"/active",outsider,Map.of("active",false),404);
        call("GET","/api/vehicles",device,null,403);
        call("GET","/api/device/vehicle",owner,null,403);
    }
    @Test void validationRejectsWeakOversizedAndUnknownFields() throws Exception {
        call("POST","/api/auth/companies",null,Map.of("companyName","회사","ownerName","대표","email","new@example.test","password","short"),400);
        call("POST","/api/auth/companies",null,Map.of("companyName","회사","ownerName","대표","email","new@example.test","password","한".repeat(30)),400);
        call("POST","/api/auth/login",null,Map.of("email","owner@example.test","password",PASSWORD,"role","OWNER"),400);
        call("GET","/api/vehicles",null,null,401);
        assertThat(response("GET","/password/setup",null,null).getResponse().getContentAsString()).contains("AutoLog 비밀번호 설정");
    }
}
