package th.co.sena.workercms;

import java.net.URI;

public enum CmsSite {
    SENA("https://sena.on.lk"),
    SENX("https://senx.on.lk");

    private final String baseUrl;

    CmsSite(String baseUrl) { this.baseUrl = baseUrl; }
    public String baseUrl() { return baseUrl; }
    public String employeeUrl() { return baseUrl + "/Common/Employee"; }

    public static CmsSite parse(String value) {
        try { return valueOf(String.valueOf(value).trim().toUpperCase()); }
        catch (Exception exception) { throw new IllegalArgumentException("กรุณาเลือก SENA หรือ SENX"); }
    }

    public static String employeeUrlFrom(String cmsUrl) {
        URI source = URI.create(cmsUrl);
        if (source.getScheme() == null || source.getHost() == null) {
            throw new IllegalArgumentException("CMS URL ไม่ถูกต้อง");
        }
        return source.getScheme() + "://" + source.getAuthority() + "/Common/Employee";
    }
}
