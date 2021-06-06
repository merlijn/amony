package com.github.merlijn.test;

import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlUtil {

    // Pattern for recognizing a URL, based off RFC 3986
    private static final Pattern urlPattern = Pattern.compile(
            "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)"
                    + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
                    + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    public static Set<String> getUrls(String raw, boolean stripArguments) {

        Matcher matcher = urlPattern.matcher(raw);

        Set<String> set = new HashSet<String>();

        while (matcher.find()) {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();

            String url = raw.substring(matchStart, matchEnd);
            int index = url.indexOf('?');

            if (index >= 0 && stripArguments)
                set.add(url.substring(0, index));
            else
                set.add(url);
        }

        return set;
    }
}
