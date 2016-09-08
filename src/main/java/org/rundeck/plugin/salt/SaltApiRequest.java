package org.rundeck.plugin.salt;

import com.google.common.collect.Lists;
import org.apache.http.NameValuePair;

import java.util.HashMap;
import java.util.List;

/**
 * Created by ben.tohsakul on 9/7/16.
 */
public class SaltApiRequest {
    public List<String> arg = Lists.newLinkedList();
    public String fun;
    public HashMap<String, String> kwarg = new HashMap<String, String>();
    public String tgt;
}
