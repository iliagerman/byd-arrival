package local.byd.arrival;

import java.util.*;

/**
 * BYD's regional servers and which one serves each country, from hass-byd-vehicle (MIT, see THIRD_PARTY_LICENSE.txt).
 * The server is always chosen from this list and never typed in, so credentials can't be sent to an arbitrary host.
 */
public final class Regions {
    /** Server id (the part of the host name that varies) to label. */
    public static final LinkedHashMap<String,String> SERVERS=new LinkedHashMap<>();
    static {
        SERVERS.put("eu","Europe");
        SERVERS.put("sg","Singapore/APAC");
        SERVERS.put("au","Australia");
        SERVERS.put("br","Brazil");
        SERVERS.put("jp","Japan");
        SERVERS.put("uz","Uzbekistan");
        SERVERS.put("no","Middle East/Africa");
        SERVERS.put("mx","Mexico/Latin America");
        SERVERS.put("id","Indonesia");
        SERVERS.put("tr","Turkey");
        SERVERS.put("kr","Korea");
        SERVERS.put("in","India");
        SERVERS.put("vn","Vietnam");
        SERVERS.put("sa","Saudi Arabia");
        SERVERS.put("om","Oman");
        SERVERS.put("kz","Kazakhstan");
    }
    public static final class Country {
        public final String name, code, server;
        Country(String name,String code,String server) { this.name=name; this.code=code; this.server=server; }
    }
    public static final List<Country> COUNTRIES=List.of(
        new Country("Albania","AL","eu"),
        new Country("Argentina","AR","mx"),
        new Country("Australia","AU","au"),
        new Country("Austria","AT","eu"),
        new Country("Bahrain","BH","no"),
        new Country("Bangladesh","BD","sg"),
        new Country("Belgium","BE","eu"),
        new Country("Bhutan","BT","sg"),
        new Country("Bolivia","BO","mx"),
        new Country("Bosnia and Herzegovina","BA","eu"),
        new Country("Brazil","BR","br"),
        new Country("Brunei","BN","sg"),
        new Country("Bulgaria","BG","eu"),
        new Country("Cambodia","KH","sg"),
        new Country("Chile","CL","mx"),
        new Country("Colombia","CO","mx"),
        new Country("Costa Rica","CR","mx"),
        new Country("Croatia","HR","eu"),
        new Country("Cyprus","CY","eu"),
        new Country("Czech Republic","CZ","eu"),
        new Country("Denmark","DK","eu"),
        new Country("Dominican Republic","DO","mx"),
        new Country("Ecuador","EC","mx"),
        new Country("Egypt","EG","no"),
        new Country("El Salvador","SV","mx"),
        new Country("Estonia","EE","eu"),
        new Country("Finland","FI","eu"),
        new Country("France","FR","eu"),
        new Country("French Polynesia","PF","sg"),
        new Country("Germany","DE","eu"),
        new Country("Greece","GR","eu"),
        new Country("Guatemala","GT","mx"),
        new Country("Honduras","HN","mx"),
        new Country("Hong Kong","HK","sg"),
        new Country("Hungary","HU","eu"),
        new Country("Iceland","IS","eu"),
        new Country("India","IN","in"),
        new Country("Indonesia","ID","id"),
        new Country("Ireland","IE","eu"),
        new Country("Israel","IL","eu"),
        new Country("Italy","IT","eu"),
        new Country("Japan","JP","jp"),
        new Country("Jordan","JO","no"),
        new Country("Kazakhstan","KZ","kz"),
        new Country("Kosovo","XK","eu"),
        new Country("Kuwait","KW","no"),
        new Country("Laos","LA","sg"),
        new Country("Latvia","LV","eu"),
        new Country("Liechtenstein","LI","eu"),
        new Country("Lithuania","LT","eu"),
        new Country("Luxembourg","LU","eu"),
        new Country("Macao","MO","sg"),
        new Country("Malaysia","MY","sg"),
        new Country("Maldives","MV","sg"),
        new Country("Malta","MT","eu"),
        new Country("Mauritius","MU","no"),
        new Country("Mexico","MX","mx"),
        new Country("Moldova","MD","eu"),
        new Country("Monaco","MC","eu"),
        new Country("Mongolia","MN","sg"),
        new Country("Montenegro","ME","eu"),
        new Country("Morocco","MA","no"),
        new Country("Myanmar","MM","sg"),
        new Country("Nepal","NP","sg"),
        new Country("Netherlands","NL","eu"),
        new Country("New Caledonia","NC","sg"),
        new Country("New Zealand","NZ","au"),
        new Country("Nicaragua","NI","mx"),
        new Country("North Macedonia","MK","eu"),
        new Country("Norway","NO","eu"),
        new Country("Oman","OM","om"),
        new Country("Pakistan","PK","sg"),
        new Country("Panama","PA","mx"),
        new Country("Paraguay","PY","mx"),
        new Country("Peru","PE","mx"),
        new Country("Philippines","PH","sg"),
        new Country("Poland","PL","eu"),
        new Country("Portugal","PT","eu"),
        new Country("Qatar","QA","no"),
        new Country("Reunion Island","RE","no"),
        new Country("Romania","RO","eu"),
        new Country("Saudi Arabia","SA","sa"),
        new Country("Serbia","RS","eu"),
        new Country("Singapore","SG","sg"),
        new Country("Slovakia","SK","eu"),
        new Country("Slovenia","SI","eu"),
        new Country("South Africa","ZA","no"),
        new Country("South Korea","KR","kr"),
        new Country("Spain","ES","eu"),
        new Country("Sri Lanka","LK","sg"),
        new Country("Sweden","SE","eu"),
        new Country("Switzerland","CH","eu"),
        new Country("Thailand","TH","sg"),
        new Country("Turkey","TR","tr"),
        new Country("Ukraine","UA","eu"),
        new Country("United Arab Emirates","AE","no"),
        new Country("United Kingdom","GB","eu"),
        new Country("Uruguay","UY","mx"),
        new Country("Uzbekistan","UZ","uz"),
        new Country("Vatican City","VA","eu"),
        new Country("Vietnam","VN","vn")
    );
    private static final String FALLBACK_COUNTRY="GB";

    public static String host(String server) {
        if(!SERVERS.containsKey(server)) throw new IllegalArgumentException("Unknown BYD server: "+server);
        return "https://dilinkappoversea-"+server+".byd.auto";
    }
    public static Country country(String code) {
        for(Country country:COUNTRIES) if(country.code.equals(code)) return country;
        throw new IllegalArgumentException("Unknown country: "+code);
    }
    /** The first listed country among what the phone reports (SIM, then language setting), to preselect at sign-in. */
    public static String preferredCountry(String... phoneCountries) {
        for(String phone:phoneCountries) {
            String code=phone.toUpperCase(Locale.ROOT);
            for(Country country:COUNTRIES) if(country.code.equals(code)) return code;
        }
        return FALLBACK_COUNTRY;
    }
}
