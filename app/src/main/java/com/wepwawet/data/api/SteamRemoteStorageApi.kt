package com.wepwawet.data.api

import com.google.gson.annotations.SerializedName
import com.wepwawet.data.model.WorkshopItem
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
 * https://steamcommunity.com/dev/apikey. v1 uses HTML scraping for browse + bulk
 * GetPublishedFileDetails for enrichment.
 *
 * NOTE: This Retrofit interface is declared for completeness but [com.wepwawet.data.SteamApiService]
 * builds the requests manually with OkHttp + Gson — Retrofit can't represent the variable-arity
 * `publishedfileids[N]` form fields cleanly.
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

// ====== Response models (parsed via Gson in [com.wepwawet.data.SteamApiService]) ======

data class GetPublishedFileDetailsResponse(
    @SerializedName("response") val response: PublishedFileDetailsResponse,
)

data class PublishedFileDetailsResponse(
    @SerializedName("result") val result: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("publishedfiledetails") val publishedFileDetails: List<WorkshopItem> = emptyList(),
)

data class GetCollectionDetailsResponse(
    @SerializedName("response") val response: CollectionDetailsResponse,
)

data class CollectionDetailsResponse(
    @SerializedName("result") val result: Int = 0,
    @SerializedName("collectioncount") val collectionCount: Int = 0,
    @SerializedName("collectiondetails") val collectionDetails: List<CollectionDetail> = emptyList(),
)

data class CollectionDetail(
    @SerializedName("collectionid") val collectionId: Long = 0,
    @SerializedName("result") val result: Int = 0,
    @SerializedName("children") val children: List<CollectionChild> = emptyList(),
)

data class CollectionChild(
    @SerializedName("publishedfileid") val publishedFileId: String,
    @SerializedName("sortorder") val sortOrder: Int = 0,
    @SerializedName("filetype") val fileType: String = "",
)

// ====== Custom exception for API errors ======

sealed class SteamApiException(message: String) : RuntimeException(message) {
    class Network(message: String, cause: Throwable) : SteamApiException(message)
    class HttpError(val statusCode: Int, message: String) : SteamApiException("HTTP $statusCode: $message")
    class ParseError(message: String, cause: Throwable) : SteamApiException(message)
    class EmptyResponse : SteamApiException("Steam returned an empty response body")
}
