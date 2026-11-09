package com.jsd.aird.kb.api;

import java.util.*;
import java.util.regex.Pattern;
import com.jsd.aird.shared.error.*;

/** A matching view only; never changes authoritative text or citation offsets. */
public final class ResearchTextMatch {
    private ResearchTextMatch() {}
    public static String normalize(String text) {
        return text.replaceAll("<[^>]+>", " ").replaceAll("(?i)\\b([a-z]{2,6})\\s+(\\d{1,4})\\b", "$1$2").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
    public static List<String> terms(String query) {
        if (query == null || query.isBlank() || query.length()>256) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var matcher=Pattern.compile("[\\p{L}\\p{N}]+(?:[.-][\\p{L}\\p{N}]+)*").matcher(normalize(query));
        var result=new LinkedHashSet<String>();
        while(matcher.find()) result.add(matcher.group());
        if(result.isEmpty() || result.size()>8) throw new ApiException(ApiErrorCode.BAD_REQUEST,"Use at most eight search terms");
        return List.copyOf(result);
    }
    public static double score(String text,String query,String mode) {
        if(!Set.of("auto","phrase","all","any").contains(mode)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var terms=terms(query); var view=normalize(text);
        long matched=terms.stream().filter(view::contains).count(); boolean phrase=view.contains(normalize(query));
        boolean accepted=switch(mode) { case "phrase" -> phrase; case "all" -> matched==terms.size(); default -> matched>0; };
        return accepted ? (phrase?2:0)+(double)matched/terms.size() : -1;
    }
}
