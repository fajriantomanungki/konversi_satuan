package id.go.konversisatuan.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Header

interface SyncApi {
    @GET("v1/approved") suspend fun approved(@Header("Authorization") bearer: String): SyncResponse
    @GET("v1/pending") suspend fun pending(@Header("Authorization") bearer: String): SyncResponse
    @POST("v1/approve/{commodity}") suspend fun approve(@Header("Authorization") bearer: String, @Path("commodity") commodity: String, @Query("quality") quality: String, @Query("localUnit") localUnit: String, @Query("standardUnit") standardUnit: String): SyncResponse
    @POST("v1/submissions") suspend fun submit(@Header("Authorization") bearer: String, @Body items: List<Conversion>): SyncResponse
}
