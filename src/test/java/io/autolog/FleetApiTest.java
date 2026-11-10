package io.autolog;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FleetApiTest extends ApiTestSupport {
    @Test void pairingIsSingleUseAndReplacesOldDevice() throws Exception {
        var pair=call("POST","/api/vehicles/"+vehicle+"/pairing",owner,null,200);
        String code=pair.path("token").asText();
        var session=call("POST","/api/device/pair",null,Map.of("pairingToken",code),200);
        call("POST","/api/device/pair",null,Map.of("pairingToken",code),401);
        call("GET","/api/device/drivers",device,null,401);
        String next=session.path("accessToken").asText();call("GET","/api/device/drivers",next,null,200);
        call("PATCH","/api/vehicles/"+vehicle,owner,Map.of("registrationNumber","12가3456","model","테스트 차량","active",false),200);
        call("GET","/api/device/drivers",next,null,401);call("POST","/api/vehicles/"+vehicle+"/pairing",owner,null,409);
    }
    @Test void pendingDriversNeedApprovalAndPreserveConsentHistory() throws Exception {
        var pending=call("POST","/api/device/drivers",device,Map.of("name","신입","department","영업"),201);
        String id=pending.path("id").asText();assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        call("POST","/api/drivers/"+id+"/approve",device,null,403);
        assertThat(call("POST","/api/drivers/"+id+"/approve",owner,null,200).path("status").asText()).isEqualTo("ACTIVE");
        call("POST","/api/device/drivers/"+id+"/consent",device,Map.of("granted",true,"noticeVersion","v1"),200);
        call("POST","/api/device/drivers/"+id+"/consent",device,Map.of("granted",false,"noticeVersion","v1"),200);
        var history=call("GET","/api/drivers/"+id+"/consents",owner,null,200);assertThat(history).hasSize(2);
        assertThat(history.get(1).path("granted").asBoolean()).isFalse();
        call("PATCH","/api/drivers/"+id,owner,Map.of("name","신입","department","영업","status","INACTIVE"),200);
        assertThat(call("GET","/api/device/drivers?q=신입",device,null,200)).isEmpty();
    }
    @Test void catalogDeactivationHidesDeviceCandidatesWithoutDeletingRows() throws Exception {
        call("PATCH","/api/catalog/purposes/"+purpose,owner,Map.of("name","고객 미팅","active",false),200);
        assertThat(call("GET","/api/device/catalog/purposes",device,null,200)).isEmpty();
        assertThat(call("GET","/api/catalog/purposes",owner,null,200)).hasSize(1);
        call("POST","/api/catalog/credentials",owner,Map.of("name","x","active",true),404);
        call("POST","/api/catalog/purposes",owner,Map.of("name","x","address","허용 안 됨","active",true),400);
    }
    @Test void foreignDriverAndCatalogCannotBeModified() throws Exception {
        String other=signup("other@example.test").path("accessToken").asText();
        call("PATCH","/api/drivers/"+driver,other,Map.of("name","변경","department","외부","status","INACTIVE"),404);
        call("PATCH","/api/catalog/clients/"+client,other,Map.of("name","변경","active",false),404);
        assertThat(call("GET","/api/drivers",other,null,200)).isEmpty();
        call("GET","/api/drivers/"+driver+"/consents",other,null,404);
    }
}
