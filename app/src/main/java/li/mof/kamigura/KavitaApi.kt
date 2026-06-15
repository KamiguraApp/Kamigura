package li.mof.kamigura

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface KavitaApi {

    @GET("api/Health")
    suspend fun health(): Unit

    @POST("api/Account/login")
    suspend fun login(@Body body: LoginDto): UserDto

    @GET("api/Library/libraries")
    suspend fun userLibraries(): List<LibraryDto>

    @POST("api/Series/all-v2")
    suspend fun allSeriesV2(
        @Body body: SeriesFilterV2Dto,
        @Query("pageNumber") pageNumber: Int? = 0,
        @Query("pageSize") pageSize: Int? = 200
    ): List<SeriesDto>

    @GET("api/Series/{seriesId}")
    suspend fun series(@Path("seriesId") id: Int): SeriesDto

    @GET("api/Series/metadata")
    suspend fun seriesMetadata(@Query("seriesId") seriesId: Int): SeriesMetadataDto

    @POST("api/Series/on-deck")
    suspend fun onDeck(
        @Query("PageNumber") pageNumber: Int? = 0,
        @Query("PageSize") pageSize: Int? = 12,
        @Query("libraryId") libraryId: Int? = 0
    ): List<SeriesDto>

    @POST("api/Series/recently-added-v2")
    suspend fun recentlyAdded(
        @Body body: SeriesFilterV2Dto = SeriesFilterV2Dto(),
        @Query("PageNumber") pageNumber: Int? = 0,
        @Query("PageSize") pageSize: Int? = 16
    ): List<SeriesDto>

    @POST("api/Series/recently-updated-series")
    suspend fun recentlyUpdatedSeries(
        @Query("PageNumber") pageNumber: Int? = 0,
        @Query("PageSize") pageSize: Int? = 16
    ): List<GroupedSeriesDto>

    @GET("api/Series/volumes")
    suspend fun volumes(@Query("seriesId") seriesId: Int): List<VolumeDto>

    @GET("api/reading-profile/{libraryId}/{seriesId}")
    suspend fun readingProfile(
        @Path("libraryId") libraryId: Int,
        @Path("seriesId") seriesId: Int,
        @Query("skipImplicit") skipImplicit: Boolean = false
    ): UserReadingProfileDto

    @GET("api/reader/chapter-info")
    suspend fun chapterInfo(
        @Query("chapterId") chapterId: Int,
        @Query("includeDimensions") includeDimensions: Boolean = false,
        @Query("extractPdf") extractPdf: Boolean = false
    ): ChapterInfoDto

    @GET("api/reader/get-progress")
    suspend fun getProgress(@Query("chapterId") chapterId: Int): ProgressDto

    @GET("api/Reader/continue-point")
    suspend fun continuePoint(@Query("seriesId") seriesId: Int): ChapterDto

    @POST("api/reader/progress")
    suspend fun saveProgress(@Body dto: ProgressDto)

    @POST("api/Reader/mark-chapter-read")
    suspend fun markChapterRead(@Body dto: MarkChapterReadDto)
}
