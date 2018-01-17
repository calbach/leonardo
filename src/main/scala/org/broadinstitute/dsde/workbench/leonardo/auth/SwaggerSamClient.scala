package org.broadinstitute.dsde.workbench.leonardo.auth

import java.io.{ByteArrayInputStream, File}
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.plus.PlusScopes
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import io.swagger.client.ApiClient
import io.swagger.client.api.{GoogleApi, ResourcesApi}
import org.broadinstitute.dsde.workbench.leonardo.model.{ClusterName, LeoException}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}


case class UserEmailAndProject(userEmail: WorkbenchEmail, googleProject: GoogleProject)

class SwaggerSamClient(samBasePath: String, cacheExpiryTime: Int, cacheMaxSize: Int,  leoEmail: WorkbenchEmail, leoPem: File) extends SamClient {

  private val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  private val jsonFactory = JacksonFactory.getDefaultInstance
  private val saScopes = Seq(PlusScopes.USERINFO_EMAIL, PlusScopes.USERINFO_PROFILE)

  private val notebookClusterResourceTypeName = "notebook-cluster"
  private val billingProjectResourceTypeName = "billing-project"


  private[auth] def googleApi(accessToken: String): GoogleApi = {
    val apiClient = new ApiClient()
    apiClient.setAccessToken(accessToken)
    apiClient.setBasePath(samBasePath)
    new GoogleApi(apiClient)
  }

  //A resources API if you already have a token
  private[auth] def resourcesApi(accessToken: String): ResourcesApi = {
    val apiClient = new ApiClient()
    apiClient.setAccessToken(accessToken)
    apiClient.setBasePath(samBasePath)
    new ResourcesApi(apiClient)
  }

  //A resources API as the given user's pet SA
  private[auth] def resourcesApiAsPet(userEmail: WorkbenchEmail, googleProject: GoogleProject): ResourcesApi = {
    resourcesApi(getCachedPetAccessToken(userEmail, googleProject))
  }

  //"Fast" lookup of pet's access token, using the cache.
  private def getCachedPetAccessToken(userEmail: WorkbenchEmail, googleProject: GoogleProject): String = {
    petTokenCache.get(UserEmailAndProject(userEmail, googleProject))
  }

  private[leonardo] val petTokenCache = CacheBuilder.newBuilder()
    .expireAfterWrite(cacheExpiryTime, TimeUnit.MINUTES)
    .maximumSize(cacheMaxSize)
    .build(
      new CacheLoader[UserEmailAndProject, String] {
        def load(userEmailAndProject: UserEmailAndProject) = {
          getPetAccessTokenFromSam(userEmailAndProject.userEmail, userEmailAndProject.googleProject)
        }
      }
    )

  //"Slow" lookup of pet's access token. The cache calls this when it needs to.
  private def getPetAccessTokenFromSam(userEmail: WorkbenchEmail, googleProject: GoogleProject): String = {
    val samAPI = googleApi(getAccessTokenUsingPem(leoEmail, leoPem))
    val userPetServiceAccountKey = samAPI.getUserPetServiceAccountKey(googleProject.value, userEmail.value)
    val keyTreeMap = userPetServiceAccountKey.asInstanceOf[LinkedTreeMap[String,String]]
    getAccessTokenUsingJson(new Gson().toJsonTree(keyTreeMap).toString)
  }

  //Given a pem, gets an access token
  private def getAccessTokenUsingPem(email: WorkbenchEmail, pem: File): String = {
    val credential = new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountId(email.value)
      .setServiceAccountScopes(saScopes.asJava)
      .setServiceAccountPrivateKeyFromPemFile(pem)
      .build()

    credential.refreshToken
    credential.getAccessToken
  }


  //Given some JSON, gets an access token
  private def getAccessTokenUsingJson(saKey: String) : String = {
    val keyStream = new ByteArrayInputStream(saKey.getBytes)
    val credential = ServiceAccountCredentials.fromStream(keyStream).createScoped(saScopes.asJava)
    credential.refreshAccessToken.getTokenValue
  }


  private def getClusterResourceId(googleProject: GoogleProject, clusterName: ClusterName): String = {
    googleProject.value + "_" + clusterName
  }


  def createNotebookClusterResource(userEmail: WorkbenchEmail, googleProject: GoogleProject, clusterName: ClusterName)(implicit executionContext: ExecutionContext) = {
    resourcesApiAsPet(userEmail, googleProject).createResource(notebookClusterResourceTypeName, getClusterResourceId(googleProject, clusterName))
  }

  def deleteNotebookClusterResource(userEmail: WorkbenchEmail, googleProject: GoogleProject, clusterName: ClusterName)(implicit executionContext: ExecutionContext) = {
    resourcesApiAsPet(userEmail, googleProject).deleteResource(notebookClusterResourceTypeName, getClusterResourceId(googleProject, clusterName))
  }

  def hasActionOnBillingProjectResource(userEmail: WorkbenchEmail, googleProject: GoogleProject, action: String)(implicit executionContext: ExecutionContext): Boolean = {
    hasActionOnResource(billingProjectResourceTypeName, googleProject.value, userEmail, googleProject, action)
  }

  def hasActionOnNotebookClusterResource(userEmail: WorkbenchEmail, googleProject: GoogleProject, clusterName: ClusterName, action: String)(implicit executionContext: ExecutionContext): Boolean = {
    hasActionOnResource(notebookClusterResourceTypeName, getClusterResourceId(googleProject, clusterName), userEmail, googleProject, action)
  }

  private def hasActionOnResource(resourceType: String, resourceName: String, userEmail: WorkbenchEmail, googleProject: GoogleProject, action: String)(implicit executionContext: ExecutionContext): Boolean = {
    resourcesApiAsPet(userEmail, googleProject).resourceAction(resourceType, resourceName, action)
  }

}