// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.testing;

import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.ResourceUtils.readResourceBytes;
import static google.registry.util.ResourceUtils.readResourceUtf8;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;
import com.google.common.io.Resources;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Contains helper methods for dealing with test data. */
public final class TestDataHelper {

  @AutoValue
  abstract static class FileKey {
    abstract Class<?> context();

    abstract String filename();

    static FileKey create(Class<?> context, String filename) {
      return new AutoValue_TestDataHelper_FileKey(context, filename);
    }
  }

  private static final Map<FileKey, String> fileCache = new ConcurrentHashMap<>();
  private static final Map<FileKey, ByteSource> byteCache = new ConcurrentHashMap<>();

  /**
   * Loads a text file from the "testdata" directory relative to the location of the specified
   * context class.
   */
  public static String loadFile(Class<?> context, String filename) {
    return fileCache.computeIfAbsent(
        FileKey.create(context, filename),
        k -> readResourceUtf8(context, "testdata/" + filename));
  }

  /**
   * Loads a text file from the "testdata" directory relative to the location of the specified
   * context class, and substitutes in values for placeholders of the form <code>%tagname%</code>.
   */
  public static String loadFile(
      Class<?> context, String filename, @Nullable Map<String, String> substitutions) {
    String fileContents = loadFile(context, filename);
    for (Entry<String, String> entry : nullToEmpty(substitutions).entrySet()) {
      fileContents = fileContents.replaceAll("%" + entry.getKey() + "%", entry.getValue());
    }
    return fileContents;
  }

  /**
   * Loads a {@link ByteSource} from the "testdata" directory relative to the location of the
   * specified context class.
   */
  public static ByteSource loadBytes(Class<?> context, String filename) {
    return byteCache.computeIfAbsent(
        FileKey.create(context, filename),
        k -> readResourceBytes(context, "testdata/" + filename));
  }

  /**
   * Returns the "real" location of the file loaded by the other commands, starting from javatests/.
   */
  public static String filePath(Class<?> context, String filename) {
    String packagePath = context.getPackage().getName().replace('.', '/');
    return String.format("javatests/%s/testdata/%s", packagePath, filename);
  }

  /** Returns a recursive iterable of all files in the given directory. */
  public static Iterable<Path> listFiles(Class<?> context, String directory) throws Exception {
    URI dir = Resources.getResource(context, directory).toURI();
    FileSystems.newFileSystem(dir, ImmutableMap.of("create", "true"));
    return MoreFiles.fileTraverser().breadthFirst(Paths.get(dir));
  }
}
