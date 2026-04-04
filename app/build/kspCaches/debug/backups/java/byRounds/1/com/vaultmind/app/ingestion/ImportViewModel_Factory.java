package com.vaultmind.app.ingestion;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class ImportViewModel_Factory implements Factory<ImportViewModel> {
  private final Provider<OnDeviceIngestion> onDeviceIngestionProvider;

  private final Provider<PackageImporter> packageImporterProvider;

  public ImportViewModel_Factory(Provider<OnDeviceIngestion> onDeviceIngestionProvider,
      Provider<PackageImporter> packageImporterProvider) {
    this.onDeviceIngestionProvider = onDeviceIngestionProvider;
    this.packageImporterProvider = packageImporterProvider;
  }

  @Override
  public ImportViewModel get() {
    return newInstance(onDeviceIngestionProvider.get(), packageImporterProvider.get());
  }

  public static ImportViewModel_Factory create(
      Provider<OnDeviceIngestion> onDeviceIngestionProvider,
      Provider<PackageImporter> packageImporterProvider) {
    return new ImportViewModel_Factory(onDeviceIngestionProvider, packageImporterProvider);
  }

  public static ImportViewModel newInstance(OnDeviceIngestion onDeviceIngestion,
      PackageImporter packageImporter) {
    return new ImportViewModel(onDeviceIngestion, packageImporter);
  }
}
