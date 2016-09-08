package org.rundeck.plugin.salt;

import com.google.common.collect.Lists;
import org.apache.http.NameValuePair;

import java.util.List;

/**
 * Created by ben.tohsakul on 9/7/16.
 */
public class SaltApiRequest {
    public List<String> args = Lists.newLinkedList();
    public String fun;
    public List<NameValuePair> kwargs = Lists.newLinkedList();
    public String tgt;
}
