/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.splitters;

import static com.android.tools.build.bundletool.model.OptimizationDimension.LANGUAGE;
import static com.android.tools.build.bundletool.testing.ManifestProtoUtils.androidManifest;
import static com.android.tools.build.bundletool.testing.ManifestProtoUtils.withTypeAttribute;
import static com.android.tools.build.bundletool.testing.TargetingUtils.apkGraphicsTargeting;
import static com.android.tools.build.bundletool.testing.TargetingUtils.apkLanguageTargeting;
import static com.android.tools.build.bundletool.testing.TargetingUtils.apkTextureTargeting;
import static com.android.tools.build.bundletool.testing.TargetingUtils.assets;
import static com.android.tools.build.bundletool.testing.TargetingUtils.assetsDirectoryTargeting;
import static com.android.tools.build.bundletool.testing.TargetingUtils.graphicsApiTargeting;
import static com.android.tools.build.bundletool.testing.TargetingUtils.languageTargeting;
import static com.android.tools.build.bundletool.testing.TargetingUtils.mergeApkTargeting;
import static com.android.tools.build.bundletool.testing.TargetingUtils.mergeAssetsTargeting;
import static com.android.tools.build.bundletool.testing.TargetingUtils.openGlVersionFrom;
import static com.android.tools.build.bundletool.testing.TargetingUtils.targetedAssetsDirectory;
import static com.android.tools.build.bundletool.testing.TargetingUtils.textureCompressionTargeting;
import static com.android.tools.build.bundletool.testing.TestUtils.extractPaths;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;

import com.android.bundle.Targeting.ApkTargeting;
import com.android.bundle.Targeting.AssetsDirectoryTargeting;
import com.android.bundle.Targeting.TextureCompressionFormat.TextureCompressionFormatAlias;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.model.BundleModule.ModuleType;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.android.tools.build.bundletool.model.ModuleSplit.SplitType;
import com.android.tools.build.bundletool.model.OptimizationDimension;
import com.android.tools.build.bundletool.testing.BundleModuleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RemoteAssetModuleSplitterTest {


  @Test
  public void singleSlice() throws Exception {
    BundleModule testModule =
        new BundleModuleBuilder("testModule")
            .addFile("assets/image.jpg")
            .addFile("assets/image2.jpg")
            .setManifest(androidManifest("com.test.app", withTypeAttribute("remote-asset")))
            .build();

    assertThat(testModule.getModuleType()).isEqualTo(ModuleType.ASSET_MODULE);
    ImmutableList<ModuleSplit> slices =
        new RemoteAssetModuleSplitter(testModule, ApkGenerationConfiguration.getDefaultInstance())
            .splitModule();

    assertThat(slices).hasSize(1);
    ModuleSplit masterSlice = slices.get(0);
    assertThat(masterSlice.getSplitType()).isEqualTo(SplitType.ASSET_SLICE);
    assertThat(masterSlice.isMasterSplit()).isTrue();
    assertThat(masterSlice.getApkTargeting()).isEqualToDefaultInstance();
    assertThat(extractPaths(masterSlice.getEntries()))
        .containsExactly("assets/image.jpg", "assets/image2.jpg");
  }

  @Test
  public void slicesByLanguage() throws Exception {
    BundleModule testModule =
        new BundleModuleBuilder("testModule")
            .addFile("assets/images/image.jpg")
            .addFile("assets/images#lang_en/image.jpg")
            .addFile("assets/images#lang_es/image.jpg")
            .setAssetsConfig(
                assets(
                    targetedAssetsDirectory(
                        "assets/images", AssetsDirectoryTargeting.getDefaultInstance()),
                    targetedAssetsDirectory(
                        "assets/images#lang_es", assetsDirectoryTargeting(languageTargeting("es"))),
                    targetedAssetsDirectory(
                        "assets/images#lang_en",
                        assetsDirectoryTargeting(languageTargeting("en")))))
            .setManifest(androidManifest("com.test.app", withTypeAttribute("remote-asset")))
            .build();

    assertThat(testModule.getModuleType()).isEqualTo(ModuleType.ASSET_MODULE);
    ImmutableList<ModuleSplit> slices =
        new RemoteAssetModuleSplitter(
                testModule,
                ApkGenerationConfiguration.builder()
                    .setOptimizationDimensions(ImmutableSet.of(LANGUAGE))
                    .build())
            .splitModule();

    assertThat(slices).hasSize(3);

    Map<ApkTargeting, ModuleSplit> slicesByTargeting =
        Maps.uniqueIndex(slices, ModuleSplit::getApkTargeting);

    assertThat(slicesByTargeting.keySet())
        .containsExactly(
            ApkTargeting.getDefaultInstance(),
            apkLanguageTargeting("es"),
            apkLanguageTargeting("en"));

    ModuleSplit defaultSlice = slicesByTargeting.get(ApkTargeting.getDefaultInstance());
    assertThat(defaultSlice.getSplitType()).isEqualTo(SplitType.ASSET_SLICE);
    assertThat(defaultSlice.isMasterSplit()).isTrue();
    assertThat(extractPaths(defaultSlice.getEntries())).containsExactly("assets/images/image.jpg");

    ModuleSplit esSlice = slicesByTargeting.get(apkLanguageTargeting("es"));
    assertThat(esSlice.getSplitType()).isEqualTo(SplitType.ASSET_SLICE);
    assertThat(esSlice.isMasterSplit()).isFalse();
    assertThat(extractPaths(esSlice.getEntries()))
        .containsExactly("assets/images#lang_es/image.jpg");

    ModuleSplit enSlice = slicesByTargeting.get(apkLanguageTargeting("en"));
    assertThat(enSlice.getSplitType()).isEqualTo(SplitType.ASSET_SLICE);
    assertThat(enSlice.isMasterSplit()).isFalse();
    assertThat(extractPaths(enSlice.getEntries()))
        .containsExactly("assets/images#lang_en/image.jpg");
  }

}