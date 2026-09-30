package com.vidhub.android.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.squareup.moshi.Moshi
import com.vidhub.android.BuildConfig
import com.vidhub.android.data.local.FavoritesStore
import com.vidhub.android.data.local.ServerConfigStore
import com.vidhub.android.data.local.SourcesCacheStore
import com.vidhub.android.data.local.WatchHistoryStore
import com.vidhub.android.data.remote.VidHubApi
import com.vidhub.android.util.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(Constants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(Constants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            // 实际请求全部使用 @Url 完整地址，baseUrl 仅为占位
            .baseUrl("https://placeholder.invalid/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideVidHubApi(retrofit: Retrofit): VidHubApi = retrofit.create(VidHubApi::class.java)

    @Provides
    @Singleton
    fun provideServerConfigStore(@ApplicationContext context: Context, moshi: Moshi): ServerConfigStore =
        ServerConfigStore(context, moshi)

    @Provides
    @Singleton
    fun provideSourcesCacheStore(@ApplicationContext context: Context, moshi: Moshi): SourcesCacheStore =
        SourcesCacheStore(context, moshi)

    @Provides
    @Singleton
    fun provideWatchHistoryStore(@ApplicationContext context: Context, moshi: Moshi): WatchHistoryStore =
        WatchHistoryStore(context, moshi)

    @Provides
    @Singleton
    fun provideFavoritesStore(@ApplicationContext context: Context, moshi: Moshi): FavoritesStore =
        FavoritesStore(context, moshi)

    @Provides
    @Singleton
    @OptIn(UnstableApi::class)
    fun provideStandaloneDatabaseProvider(
        @ApplicationContext context: Context,
    ): StandaloneDatabaseProvider = StandaloneDatabaseProvider(context)

    @Provides
    @Singleton
    @OptIn(UnstableApi::class)
    fun provideSimpleCache(
        @ApplicationContext context: Context,
        dbProvider: StandaloneDatabaseProvider,
    ): SimpleCache =
        SimpleCache(
            File(context.cacheDir, "media3cache"),
            LeastRecentlyUsedCacheEvictor(Constants.PLAYER_CACHE_MAX_BYTES),
            dbProvider,
        )

    @Provides
    @Singleton
    @OptIn(UnstableApi::class)
    fun provideCacheDataSourceFactory(
        @ApplicationContext context: Context,
        cache: SimpleCache,
    ): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            // 缓存未命中必须回源（默认 upstream=null 会走 PlaceholderDataSource 抛 IOException）
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
}
