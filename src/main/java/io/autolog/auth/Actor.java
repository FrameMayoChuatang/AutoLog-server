package io.autolog.auth;

import io.autolog.common.ApiException;
import java.util.UUID;

public record Actor(UUID companyId, UUID adminId, UUID vehicleId, UUID credentialId, String role) {
    public boolean isDevice() { return "DEVICE".equals(role); }
    public void requireAdmin() { if (isDevice()) throw ApiException.denied(); }
    public void requireOwner() { if (!"OWNER".equals(role)) throw ApiException.denied(); }
    public void requireDevice() { if (!isDevice()) throw ApiException.denied(); }
}
