package org.broadinstitute.dsde.workbench.leonardo

import java.io.File

import org.broadinstitute.dsde.workbench.service.{Orchestration, RestException, Sam}
import org.broadinstitute.dsde.workbench.dao.Google.{googleIamDAO, googleStorageDAO}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures
import org.broadinstitute.dsde.workbench.model.google.GcsEntityTypes.Group
import org.broadinstitute.dsde.workbench.model.google.GcsRoles.Reader
import org.broadinstitute.dsde.workbench.model.google.{GcsEntity, GcsObjectName, GcsPath, GoogleProject, parseGcsPath}
import org.scalactic.source.Position
import org.scalatest.{FreeSpec, ParallelTestExecution}

import scala.util.{Success, Try}

class ClusterMonitoringSpec extends FreeSpec with LeonardoTestUtils with ParallelTestExecution with BillingFixtures {
  "Leonardo clusters" - {

    "should create, monitor, delete, recreate, and re-delete a cluster" in {
      withCleanBillingProject(hermioneCreds) { projectName =>
        Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)
        val project = GoogleProject(projectName)
        implicit val token = ronAuthToken
        val nameToReuse = randomClusterName

        // create, monitor, delete once
        withNewCluster(project, nameToReuse)(_ => ())

        // create, monitor, delete again with same name
        withNewCluster(project, nameToReuse)(_ => ())
      }
    }

    "should error on cluster create and delete the cluster" in {
      withCleanBillingProject(hermioneCreds) { projectName =>
        Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)
        implicit val token = ronAuthToken
        withNewErroredCluster(GoogleProject(projectName)) { _ =>
          // no-op; just verify that it launches
        }
      }
    }

    // default PetClusterServiceAccountProvider edition
    "should create a cluster in a different billing project using PetClusterServiceAccountProvider and put the pet's credentials on the cluster" in withWebDriver { implicit driver =>
      withCleanBillingProject(hermioneCreds) { projectName =>
        val project = GoogleProject(projectName)

        Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

        implicit val token = ronAuthToken
        // Pre-conditions: pet service account exists in this Google project and in Sam
        val (petName, petEmail) = getAndVerifyPet(project)

        // Create a cluster

        withNewCluster(project) { cluster =>
          // cluster should have been created with the pet service account
          cluster.serviceAccountInfo.clusterServiceAccount shouldBe Some(petEmail)
          cluster.serviceAccountInfo.notebookServiceAccount shouldBe None

          withNewNotebook(cluster) { notebookPage =>
            // should not have notebook credentials because Leo is not configured to use a notebook service account
            verifyNoNotebookCredentials(notebookPage)
          }
        }

        // Post-conditions: pet should still exist in this Google project

        implicit val patienceConfig: PatienceConfig = saPatience
        val googlePetEmail2 = googleIamDAO.findServiceAccount(project, petName).futureValue.map(_.email)
        googlePetEmail2 shouldBe Some(petEmail)
      }
    }

    // PetNotebookServiceAccountProvider edition.  IGNORE.
    "should create a cluster in a different billing project using PetNotebookServiceAccountProvider and put the pet's credentials on the cluster" ignore withWebDriver { implicit driver =>
      withCleanBillingProject(hermioneCreds) { projectName =>
        val project = GoogleProject(projectName)

        Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

        implicit val token = ronAuthToken
        // Pre-conditions: pet service account exists in this Google project and in Sam
        val (petName, petEmail) = getAndVerifyPet(project)

        // Create a cluster

        withNewCluster(project) { cluster =>
          // cluster should have been created with the default cluster account
          cluster.serviceAccountInfo.clusterServiceAccount shouldBe None
          cluster.serviceAccountInfo.notebookServiceAccount shouldBe Some(petEmail)

          withNewNotebook(cluster) { notebookPage =>
            // should have notebook credentials
            verifyNotebookCredentials(notebookPage, petEmail)
          }
        }

        // Post-conditions: pet should still exist in this Google project

        implicit val patienceConfig: PatienceConfig = saPatience
        val googlePetEmail2 = googleIamDAO.findServiceAccount(project, petName).futureValue.map(_.email)
        googlePetEmail2 shouldBe Some(petEmail)
      }
    }

    // TODO: we've noticed intermittent failures for this test. See:
    // https://github.com/DataBiosphere/leonardo/issues/204
    // https://github.com/DataBiosphere/leonardo/issues/228
    "should foo execute Hail with correct permissions on a cluster with preemptible workers" in withWebDriver { implicit driver =>
      withCleanBillingProject(hermioneCreds) { projectName =>
        val project = GoogleProject(projectName)

        Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

        withNewGoogleBucket(project) { bucket =>
          implicit val patienceConfig: PatienceConfig = storagePatience

          val srcPath = parseGcsPath("gs://genomics-public-data/1000-genomes/vcf/ALL.chr20.integrated_phase1_v3.20101123.snps_indels_svs.genotypes.vcf").right.get
          val destPath = GcsPath(bucket, GcsObjectName("chr20.vcf"))
          googleStorageDAO.copyObject(srcPath.bucketName, srcPath.objectName, destPath.bucketName, destPath.objectName).futureValue

          implicit val token = ronAuthToken
          val ronProxyGroup = Sam.user.proxyGroup(ronEmail)
          val ronPetEntity = GcsEntity(ronProxyGroup, Group)
          googleStorageDAO.setObjectAccessControl(destPath.bucketName, destPath.objectName, ronPetEntity, Reader).futureValue

          val request = ClusterRequest(machineConfig = Option(MachineConfig(
            // need at least 2 regular workers to enable preemptibles
            numberOfWorkers = Option(2),
            numberOfPreemptibleWorkers = Option(10)
          )))

          withNewCluster(project, request = request) { cluster =>
            withNewNotebook(cluster) { notebookPage =>
              verifyHailImport(notebookPage, destPath, cluster.clusterName)
            }
          }
        }
      }
    }

    "should pause and resume a cluster" in withWebDriver { implicit driver =>
      withBrandNewBillingProject("leo-test", List(hermioneCreds.email)) { projectName =>
        val project = GoogleProject(projectName)

        Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

        implicit val token = ronAuthToken

        // Create a cluster
        withNewCluster(project) { cluster =>
          val printStr = "Pause/resume test"

          // Create a notebook and execute a cell
          withNewNotebook(cluster, kernel = Python3) { notebookPage =>
            notebookPage.executeCell(s"""print("$printStr")""") shouldBe Some(printStr)
            notebookPage.saveAndCheckpoint()
          }

          // Stop the cluster
          stopAndMonitor(cluster.googleProject, cluster.clusterName)

          // Verify notebook error
          val caught = the [RestException] thrownBy {
            Leonardo.notebooks.getApi(cluster.googleProject, cluster.clusterName)
          }
          caught.message shouldBe s"""{"statusCode":422,"source":"leonardo","causes":[],"exceptionClass":"org.broadinstitute.dsde.workbench.leonardo.service.ClusterPausedException","stackTrace":[],"message":"Cluster ${projectName}/${cluster.clusterName.string} is stopped. Start your cluster before proceeding."}"""

          // Start the cluster
          startAndMonitor(cluster.googleProject, cluster.clusterName)

          // TODO cluster is not proxyable yet even after Google says it's ok
          eventually {
            logger.info("Checking if cluster is proxyable yet")
            val getResult = Try(Leonardo.notebooks.getApi(cluster.googleProject, cluster.clusterName))
            logger.info("Proxy get result is " + getResult)
            getResult.isSuccess shouldBe true
            getResult.get should not include "ProxyException"

          }(startPatience, implicitly[Position])

          logger.info("Sleeping 1 minute to make sure restarted cluster is proxyable")
          Thread.sleep(60*1000)

          // TODO make tests rename notebooks?
          val notebookPath = new File("Untitled.ipynb")
          withOpenNotebook(cluster, notebookPath) { notebookPage =>
            // old output should still exist
            logger.info("Checking for existing cell output")
            notebookPage.cellOutput(notebookPage.firstCell) shouldBe Some(printStr)
            // execute a new cell to make sure the notebook kernel still works
            logger.info("Executing new cell")
            notebookPage.executeCell("sum(range(1,10))") shouldBe Some("45")
          }
        }

      }(hermioneAuthToken)
    }

    "should be able to delete a stopped cluster" in withWebDriver { implicit driver =>
      withCleanBillingProject(hermioneCreds) { projectName =>
        val project = GoogleProject(projectName)

        Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

        implicit val token = ronAuthToken

        // Create a cluster
        withNewCluster(project) { cluster =>
          // Stop the cluster
          stopAndMonitor(cluster.googleProject, cluster.clusterName)
        }
        // Delete should succeed
      }
    }

  }

}
