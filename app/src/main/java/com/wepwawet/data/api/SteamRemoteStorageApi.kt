package com.wepwawet.data.api

import com.wepwawet.data.model.WorkshopItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field

/**
 * Steam Web API endpoints WepWawet uses for workshop browsing.
 *
 * All endpoints below are **anonymous-accessible** (no `key=` parameter required),
 * as confirmed by research Task 1-C by hitting them live.
 *
 * What we get anonymously:
 *   - Full metadata (title, description, tags, file_size, preview_url, subscriptions, lifetime_*)
 *   - Collection membership
 *
 * What we DON'T get anonymously:
 *   - `file_url` is always empty (Steam hides it)
 *   - Search/filter via IPublishedFileService/QueryFiles (requires key)
 *   - List subscribed items (requires key + user token)
 *
 * Authenticated endpoints (IPublishedFileService) would let us search/filter properly,
 * but they require a free Steam Web API key the user can obtain at
 * https://steamcommunity.com/dev/apikey. v1 will use HTML scraping for browse + bulk
 * GetPublishedFileDetails for enrichment.
 */
interface SteamRemoteStorageApi {

    /**
     * Bulk-fetch workshop item metadata by publishedfileid.
     *
     * Usage:
     *   POST https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/
     *   body: itemcount=2&publishedfileids[0]=12345&publishedfileids[1]=67890
     *
     * Anonymous access confirmed.
     *
     * Note: this single-item declaration is a Retrofit formality — for the multi-item case
     * we use the [com.wepwawet.data.SteamApiService.getBatchDetails] impl which builds the
     * FormBody manually with OkHttp.
     */
    @FormUrlEncoded
    @POST("ISteamRemoteStorage/GetPublishedFileDetails/v1/")
    suspend fun getPublishedFileDetails(
        @Field("itemcount") itemCount: Int,
        @Field("publishedfileids[0]") id0: String,
    ): GetPublishedFileDetailsResponse

    /**
     * Get details for a collection (so we can browse a curated playlist).
     *
     * Anonymous access confirmed.
     */
    @FormUrlEncoded
    @POST("ISteamRemoteStorage/GetCollectionDetails/v1/")
    suspend fun getCollectionDetails(
        @Field("collectioncount") collectionCount: Int,
        @Field("collectionids[0]") collectionId: String,
    ): GetCollectionDetailsResponse
}

// ====== Response models ======

@Serializable
data class GetPublishedFileDetailsResponse(
    @SerialName("response") val response: PublishedFileDetailsResponse
)

@Serializable
data class PublishedFileDetailsResponse(
    @SerialName("result") val result: Int = 0,
    @SerialName("total") val total: Int = 0,
    @SerialName("publishedfiledetails") val publishedFileDetails: List<WorkshopItem> = emptyList(),
)

@Serializable
data class GetCollectionDetailsResponse(
    @SerialName("response") val response: CollectionDetailsResponse
)

@Serializable
data class CollectionDetailsResponse(
    @SerialName("result") val result: Int = 0,
    @SerialName("collectioncount") val collectionCount: Int = 0,
    @SerialName("collectiondetails") val collectionDetails: List<CollectionDetail> = emptyList(),
)

@Serializable
data class CollectionDetail(
    @SerialName("collectionid") val collectionId: Long = 0,
    @SerialName("result") val result: Int = 0,
    @SerialName("children") val children: List<CollectionChild> = emptyList(),
)

@Serializable
data class CollectionChild(
    @SerialName("publishedfileid") val publishedFileId: String,
    @SerialName("sortorder") val sortOrder: Int = 0,
    @SerialName("filetype") val fileType: String = "",
)

// ====== Custom exception for API errors ======

sealed class SteamApiException(message: String) : RuntimeException(message) {
    class Network(message: String, cause: Throwable) : SteamApiException(message)
    class HttpError(val statusCode: Int, message: String) : SteamApiException("HTTP $statusCode: $message")
    class ParseError(message: String, cause: Throwable) : SteamApiException(message)
    class EmptyResponse : SteamApiException("Steam returned an empty response body")
}
