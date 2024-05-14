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

import com.google.auto.value.AutoValue;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A value that represents the 'mappings' of an external Bazel workspace, as defined in the main
 * WORKSPACE file. The SkyValue contains the mappings themselves, with the key being the name of the
 * external repository.
 *
 * <p>Given the following rule:
 *
 * <pre>{@code
 * local_repository(
 *   name = "a",
 *   path = "../a",
 *   repo_mapping = {"@x" : "@y"}
 * )
 * }</pre>
 *
 * <p>The SkyKey would be {@code "@a"} and the SkyValue would be the map {@code {"@x" : "@y"}}
 *
 * <p>This is kept as a separate value with trivial change pruning so as to not necessitate a
 * dependency from every {@link PackageValue} to the //external {@link PackageValue}, so that
 * changes to things in the WORKSPACE other than the mappings (and name) won't require reloading all
 * packages. If the mappings are changed then the external packages need to be reloaded.
 */
@AutoValue
public abstract class RepositoryMappingValue implements SkyValue {
  public static final Key KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS =
      Key.create(RepositoryName.MAIN, /* rootModuleShouldSeeWorkspaceRepos= */ false);

  public static final RepositoryMappingValue VALUE_FOR_ROOT_MODULE_WITHOUT_REPOS =
      RepositoryMappingValue.createForWorkspaceRepo(RepositoryMapping.ALWAYS_FALLBACK);

  public static final RepositoryMappingValue NOT_FOUND_VALUE =
      RepositoryMappingValue.createForWorkspaceRepo(null);

  /**
   * Returns a {@link RepositoryMappingValue} for a repo defined in MODULE.bazel, which has an
   * associated module.
   */
  public static RepositoryMappingValue createForBzlmodRepo(
      RepositoryMapping repositoryMapping,
      String associatedModuleName,
      Version associatedModuleVersion) {
    return new AutoValue_RepositoryMappingValue(
        repositoryMapping,
        Optional.of(associatedModuleName),
        Optional.of(associatedModuleVersion.getOriginal()));
  }

  /**
   * Returns a {@link RepositoryMappingValue} for a repo defined in WORKSPACE, which has no
   * associated module.
   */
  public static RepositoryMappingValue createForWorkspaceRepo(RepositoryMapping repositoryMapping) {
    return new AutoValue_RepositoryMappingValue(
        repositoryMapping, Optional.empty(), Optional.empty());
  }

  /** The actual repo mapping. Will be null if the requested repo doesn't exist. */
  @Nullable
  public abstract RepositoryMapping getRepositoryMapping();

  /**
   * Returns the name of the Bzlmod module associated with the requested repo. If the requested repo
   * is defined in WORKSPACE, this is empty. For repos generated by module extensions, this is the
   * name of the module hosting the extension.
   */
  public abstract Optional<String> getAssociatedModuleName();

  /**
   * Returns the version of the Bzlmod module associated with the requested repo. If the requested
   * repo is defined in WORKSPACE, this is empty. For repos generated by module extensions, this is
   * the version of the module hosting the extension.
   */
  public abstract Optional<String> getAssociatedModuleVersion();

  /**
   * Replaces the inner {@link #getRepositoryMapping() repository mapping} with one returned by
   * calling its {@link RepositoryMapping#withAdditionalMappings} method.
   */
  public final RepositoryMappingValue withAdditionalMappings(Map<String, RepositoryName> mappings) {
    return new AutoValue_RepositoryMappingValue(
        getRepositoryMapping().withAdditionalMappings(mappings),
        getAssociatedModuleName(),
        getAssociatedModuleVersion());
  }

  /**
   * Replaces the inner {@link #getRepositoryMapping() repository mapping} with one returned by
   * calling its {@link RepositoryMapping#withCachedInverseMap} method.
   */
  public final RepositoryMappingValue withCachedInverseMap() {
    return new AutoValue_RepositoryMappingValue(
        getRepositoryMapping().withCachedInverseMap(),
        getAssociatedModuleName(),
        getAssociatedModuleVersion());
  }

  /** Returns the {@link Key} for {@link RepositoryMappingValue}s. */
  public static Key key(RepositoryName repositoryName) {
    return RepositoryMappingValue.Key.create(
        repositoryName, /* rootModuleShouldSeeWorkspaceRepos= */ true);
  }

  /** {@link SkyKey} for {@link RepositoryMappingValue}. */
  @AutoValue
  public abstract static class Key implements SkyKey {

    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    /** The name of the repo to grab mappings for. */
    public abstract RepositoryName repoName();

    /**
     * Whether the root module should see repos defined in WORKSPACE. This only takes effect when
     * {@link #repoName} is the main repo.
     */
    abstract boolean rootModuleShouldSeeWorkspaceRepos();

    static Key create(RepositoryName repoName, boolean rootModuleShouldSeeWorkspaceRepos) {
      return interner.intern(
          new AutoValue_RepositoryMappingValue_Key(repoName, rootModuleShouldSeeWorkspaceRepos));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static Key intern(Key key) {
      return interner.intern(key);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.REPOSITORY_MAPPING;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  /**
   * Exception thrown where the RepositoryMappingValue is requested and its computation fails for
   * any reason.
   */
  public static class RepositoryMappingResolutionException extends Exception {

    private final DetailedExitCode detailedExitCode;

    public RepositoryMappingResolutionException(DetailedExitCode detailedExitCode) {
      super(
          String.format(
              "Error computing the main repository mapping: %s",
              detailedExitCode.getFailureDetail().getMessage()));
      this.detailedExitCode = detailedExitCode;
    }

    public RepositoryMappingResolutionException(
        DetailedExitCode detailedExitCode, Throwable cause) {
      super(
          String.format(
              "Error computing the main repository mapping: %s",
              detailedExitCode.getFailureDetail().getMessage()),
          cause);
      this.detailedExitCode = detailedExitCode;
    }

    public ExitCode getExitCode() {
      return detailedExitCode.getExitCode();
    }

    public DetailedExitCode getDetailedExitCode() {
      return detailedExitCode;
    }
  }
}