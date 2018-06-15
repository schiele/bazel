// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ActionFileSystem}.
 *
 * It would be nice to derive from
 * {@link com.google.devtools.build.lib.vfs.FileSystemTest;FileSystemTest} if/when ActionFileSystem
 * becomes sufficiently general.
 */
@RunWith(JUnit4.class)
public class ActionFileSystemTest {
  private ActionFileSystem actionFS;
  private PathFragment execRootFragment;
  private Path outputPath;

  @Before
  public void freshFS() {
    FileSystem delegateFS = new InMemoryFileSystem();
    execRootFragment = PathFragment.create("/path/to/execroot");
    String relativeOutputPath = "goog-out";
    actionFS = new ActionFileSystem(delegateFS, execRootFragment, relativeOutputPath,
        ImmutableList.of(), new ActionInputMap(0), ImmutableList.of(), ImmutableList.of());
    outputPath = actionFS.getPath(execRootFragment.getRelative(relativeOutputPath));
  }

  @Test
  public void testFileWrite() throws Exception {
    String testData = "abc19";

    Path file = outputPath.getRelative("foo/bar");
    FileSystemUtils.writeContentAsLatin1(file, testData);
    assertThat(file.getFileSize()).isEqualTo(testData.length());
    assertThat(file.exists()).isTrue();
    assertThat(file.stat().isFile()).isTrue();
    assertThat(file.stat().isDirectory()).isFalse();

    assertThat(file.delete()).isTrue();
    assertThat(file.exists()).isFalse();
    assertThat(file.delete()).isFalse();
  }

  @Test
  public void testInjectUndeclaredOutput() throws Exception {
    String testData = "abc19";
    byte[] fileContent = testData.getBytes();
    HashCode digest = Hashing.md5().hashBytes(fileContent);
    Path file = outputPath.getRelative("foo/bar");
    assertThat(file.exists()).isFalse();
    actionFS.onInsert(asActionInput(file), digest.asBytes(), fileContent.length, 83);

    assertThat(file.exists()).isTrue();
    assertThat(file.stat().getSize()).isEqualTo(fileContent.length);
    assertThat(file.getDigest()).isEqualTo(digest.asBytes());
    assertThat(file.getFastDigest()).isEqualTo(digest.asBytes());

    assertThat(file.delete()).isTrue();
    assertThat(file.exists()).isFalse();
    assertThat(file.delete()).isFalse();
  }

  private ActionInput asActionInput(Path path) {
    return ActionInputHelper.fromPath(path.asFragment().relativeTo(execRootFragment));
  }

  @Test
  public void testAppendLocalFile() throws Exception {
    String testData = "abc";

    Path file = outputPath.getRelative("foo/bar");
    FileSystemUtils.writeContentAsLatin1(file, testData);
    assertThat(new String(FileSystemUtils.readContentAsLatin1(file))).isEqualTo(testData);

    try (OutputStream out = file.getOutputStream(true)) {
      PrintStream printStream = new PrintStream(out);
      printStream.append("defg");
      printStream.flush();
    }
    assertThat(new String(FileSystemUtils.readContentAsLatin1(file))).isEqualTo("abcdefg");

    // Now make sure we can still overwrite the file in non-append mode.
    FileSystemUtils.writeContentAsLatin1(file, "cheesy");
    assertThat(new String(FileSystemUtils.readContentAsLatin1(file))).isEqualTo("cheesy");
  }

  @Test
  public void testFlushedButNotClosedFileWrite() throws Exception {
    String testData = "abc19";

    Path file = outputPath.getRelative("foo/bar");
    try (OutputStream out = file.getOutputStream()) {
      assertThat(file.exists()).isFalse();

      out.write(testData.getBytes(StandardCharsets.ISO_8859_1));
      assertThat(file.exists()).isFalse();

      out.flush();
      assertThat(file.getFileSize()).isEqualTo(testData.length());
      assertThat(file.exists()).isTrue();
      assertThat(file.stat().isFile()).isTrue();
      assertThat(file.stat().isDirectory()).isFalse();
    }
    assertThat(file.getFileSize()).isEqualTo(testData.length());
  }
}
