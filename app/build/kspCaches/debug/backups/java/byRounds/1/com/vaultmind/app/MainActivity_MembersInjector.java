package com.vaultmind.app;

import com.vaultmind.app.settings.AppPreferences;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class MainActivity_MembersInjector implements MembersInjector<MainActivity> {
  private final Provider<AppPreferences> appPreferencesProvider;

  public MainActivity_MembersInjector(Provider<AppPreferences> appPreferencesProvider) {
    this.appPreferencesProvider = appPreferencesProvider;
  }

  public static MembersInjector<MainActivity> create(
      Provider<AppPreferences> appPreferencesProvider) {
    return new MainActivity_MembersInjector(appPreferencesProvider);
  }

  @Override
  public void injectMembers(MainActivity instance) {
    injectAppPreferences(instance, appPreferencesProvider.get());
  }

  @InjectedFieldSignature("com.vaultmind.app.MainActivity.appPreferences")
  public static void injectAppPreferences(MainActivity instance, AppPreferences appPreferences) {
    instance.appPreferences = appPreferences;
  }
}
