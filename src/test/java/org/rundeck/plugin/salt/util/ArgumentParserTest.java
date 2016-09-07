/**
 * Copyright (c) 2013, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided
 * that the following conditions are met:
 *
 *    Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *    following disclaimer.
 *
 *    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and
 *    the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *    Neither the name of salesforce.com, inc. nor the names of its contributors may be used to endorse or
 *    promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.rundeck.plugin.salt.util;

import java.util.List;

import org.apache.http.NameValuePair;
import org.junit.Assert;
import org.junit.Test;

public class ArgumentParserTest {

    @Test
    public void testParse() {
        List<NameValuePair> args = new ArgumentParser("\\s").parse("1 2 3");
        Assert.assertEquals(3, args.size());
        Assert.assertEquals("1", args.get(0).getValue());
        Assert.assertEquals("2", args.get(1).getValue());
        Assert.assertEquals("3", args.get(2).getValue());
    }

    @Test
    public void testParseWithDoubleQuotedString() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '"' }).parse("\"1 2\" 3");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("1 2", args.get(0).getValue());
        Assert.assertEquals("3", args.get(1).getValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseWithDoubleQuotedLeadingUnbalancedString() {
        new ArgumentParser("\\s", new char[] { '"' }).parse("\"1 2");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseWithDoubleQuotedTrailingUnbalancedString() {
        new ArgumentParser("\\s", new char[] { '"' }).parse("1 2\"");
    }

    @Test
    public void testParseWithSingleQuotedString() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'' }).parse("'1 2' 3");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("1 2", args.get(0).getValue());
        Assert.assertEquals("3", args.get(1).getValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseWithSingleQuotedLeadingUnbalancedString() {
        new ArgumentParser("\\s", new char[] { '\'' }).parse("'1 2");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseWithSingleQuotedTrailingUnbalancedString() {
        new ArgumentParser("\\s", new char[] { '\'' }).parse("1 2'");
    }

    @Test
    public void testParseWithSingleAndDoubleQuotedString() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }).parse("'1 2' \"3 4\"");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("1 2", args.get(0).getValue());
        Assert.assertEquals("3 4", args.get(1).getValue());
    }

    @Test
    public void testParseWithSingleAndDoubleQuotedStringWithoutSeparator() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }).parse("'1 2'\"3 4\"");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("1 2", args.get(0).getValue());
        Assert.assertEquals("3 4", args.get(1).getValue());
    }

    @Test
    public void testParseWithSingleQuotedStringWithoutSeparator() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }).parse("0'1 2'3");
        Assert.assertEquals(3, args.size());
        Assert.assertEquals("0", args.get(0).getValue());
        Assert.assertEquals("1 2", args.get(1).getValue());
        Assert.assertEquals("3", args.get(2).getValue());
    }

    @Test
    public void testParseWithTabs() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '"' }).parse("\"1\t2\"\t3");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("1\t2", args.get(0).getValue());
        Assert.assertEquals("3", args.get(1).getValue());
    }

    @Test
    public void testParseWithMultipleSpaces() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '"' }).parse("\"1 2\"     3");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("1 2", args.get(0).getValue());
        Assert.assertEquals("3", args.get(1).getValue());
    }

    @Test
    public void testParseSingleQuoteWithNestedDoubleQuotes() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }, '\\')
                .parse("cmd.run 'echo \"some message\"'");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("cmd.run", args.get(0).getValue());
        Assert.assertEquals("echo \"some message\"", args.get(1).getValue());
    }

    @Test
    public void testParseDoubleQuoteWithNestedSingleQuotes() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }, '\\')
                .parse("cmd.run \"echo 'some message'\"");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("cmd.run", args.get(0).getValue());
        Assert.assertEquals("echo 'some message'", args.get(1).getValue());
    }

    @Test
    public void testParseSingleQuoteWithNestedEscapedSingleQuotes() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }, '\\')
                .parse("cmd.run 'echo \\'some message\\''");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("cmd.run", args.get(0).getValue());
        Assert.assertEquals("echo 'some message'", args.get(1).getValue());
    }

    @Test
    public void testParseDoubleQuoteWithNestedEscapedDoubleQuotes() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }, '\\')
                .parse("cmd.run \"echo \\\"some message\\\"\"");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("cmd.run", args.get(0).getValue());
        Assert.assertEquals("echo \"some message\"", args.get(1).getValue());
    }
    
    @Test
    public void testParseEscapeCharacterWithoutFollowingQuote() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }, '\\')
                .parse("cmd.run \"echo \\\"some m\\essage\\\"\"");
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("cmd.run", args.get(0).getValue());
        Assert.assertEquals("echo \"some m\\essage\"", args.get(1).getValue());
    }
    
    @Test
    public void testParseTrailingEscapeCharacterWithoutFollowingQuote() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }, '\\')
                .parse("cmd.run \"echo \\\"some message\\\"\"\\");
        Assert.assertEquals(3, args.size());
        Assert.assertEquals("cmd.run", args.get(0).getValue());
        Assert.assertEquals("echo \"some message\"", args.get(1).getValue());
        Assert.assertEquals("\\", args.get(2).getValue());
    }
    
    @Test
    public void testParseMultiline() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }, '\\')
                .parse("file.append /tmp/some_script.sh \"#! /bin/bash -x \nfor i in `ls /tmp`; do\n  ls -la /tmp/$i\ndone\"");
        Assert.assertEquals(3, args.size());
        Assert.assertEquals("file.append", args.get(0).getValue());
        Assert.assertEquals("/tmp/some_script.sh", args.get(1).getValue());
        Assert.assertEquals("#! /bin/bash -x \nfor i in `ls /tmp`; do\n  ls -la /tmp/$i\ndone", args.get(2).getValue());
    }
    
    @Test
    public void testParseEmptyString() {
        Assert.assertEquals(0, new ArgumentParser("\\s").parse("").size());
    }

    @Test(expected = NullPointerException.class)
    public void testParseNullString() {
        new ArgumentParser("\\s").parse(null);
    }

    @Test
    public void testDefaultArgumentSplitter() {
        Assert.assertEquals("\\s", ArgumentParser.DEFAULT_ARGUMENT_SPLITTER.separatorCharSetRegex);
        char[] quoteCharacters = ArgumentParser.DEFAULT_ARGUMENT_SPLITTER.quoteCharacters;
        Assert.assertEquals('"', quoteCharacters[0]);
        Assert.assertEquals('\'', quoteCharacters[1]);
        Assert.assertEquals('\\', ArgumentParser.DEFAULT_ARGUMENT_SPLITTER.escapeCharacter);
    }

    @Test
    public void testParseArgsWithKwargs() {
        List<NameValuePair> args = new ArgumentParser("\\s", new char[] { '\'', '"' }, '\\').parse("module.command arg '\"single quoted arg\"' \"'double quoted arg'\" namedArg=namedArgValue namedSingleQuotedArg='\"named single quoted arg\"' namedDoubleQuotedArg=\"'named double quoted arg'\"");
        Assert.assertEquals(7, args.size());
        Assert.assertEquals("module.command", args.get(0).getValue());

        Assert.assertEquals("arg", args.get(1).getValue());
        Assert.assertEquals("\"single quoted arg\"", args.get(2).getValue());
        Assert.assertEquals("'double quoted arg'", args.get(3).getValue());

        Assert.assertEquals("namedArg", args.get(4).getName());
        Assert.assertEquals("namedArgValue", args.get(4).getValue());

        Assert.assertEquals("namedSingleQuotedArg", args.get(5).getName());
        Assert.assertEquals("\"named single quoted arg\"", args.get(5).getValue());

        Assert.assertEquals("namedDoubleQuotedArg", args.get(6).getName());
        Assert.assertEquals("'named double quoted arg'", args.get(6).getValue());
    }
}
