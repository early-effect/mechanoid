package mechanoid
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import saferis.{ConnectionProvider, SaferisError, Transactor}
import mechanoid.persistence.postgres.PostgresSchema
import zio.*

import javax.sql.DataSource

final case class ContainerConfig(
    imageName: String = s"${PostgreSQLContainer.IMAGE}:latest"
)
object ContainerConfig:
  val default = ZLayer.succeed(ContainerConfig())

final case class PostgresTestContainer(
    config: ContainerConfig
):
  val postgres: PostgreSQLContainer[?] =
    val container: PostgreSQLContainer[?] =
      new PostgreSQLContainer(config.imageName)
        // Disable password checks
        .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
    container

  def start: PostgresTestContainer =
    postgres.start()
    this

  val stop: UIO[Unit] =
    ZIO.succeed:
      postgres.stop()
end PostgresTestContainer

object PostgresTestContainer:
  val base  = ZLayer.derive[PostgresTestContainer]
  val layer = base >>> ZLayer.scoped:
    ZIO.acquireRelease(ZIO.service[PostgresTestContainer].map(_.start))(_.stop)
  val default = ContainerConfig.default >>> layer

  final case class DataSourceProvider(container: PostgresTestContainer):
    import container.postgres

    def dataSource: DataSource =
      val ds = PGSimpleDataSource()
      ds.setURL(postgres.getJdbcUrl())
      ds.setUser(postgres.getUsername())
      ds.setPassword(postgres.getPassword())
      ds
  end DataSourceProvider

  object DataSourceProvider:
    def makeDataSource(container: PostgresTestContainer): DataSource =
      val ds = PGSimpleDataSource()
      ds.setURL(container.postgres.getJdbcUrl())
      ds.setUser(container.postgres.getUsername())
      ds.setPassword(container.postgres.getPassword())
      ds

    def makeConnectionProvider(container: PostgresTestContainer): ConnectionProvider =
      ConnectionProvider.FromDataSource(makeDataSource(container))

    val datasource: URLayer[PostgresTestContainer, DataSource] =
      ZLayer.derive[DataSourceProvider].map(env => ZEnvironment(env.get.dataSource))

    val provider: ZLayer[PostgresTestContainer, Nothing, ConnectionProvider] =
      ZLayer.fromFunction(makeConnectionProvider)

    val default: ZLayer[Any, Nothing, ConnectionProvider] =
      PostgresTestContainer.default >>> provider

    /** Transactor layer with schema initialization using PostgresSchema.initialize. */
    val transactor: ZLayer[Any, SaferisError, Transactor] =
      default >>> Transactor.default >>> ZLayer.fromZIO {
        PostgresSchema.initialize.flatMap { result =>
          ZIO.logInfo(s"PostgresSchema initialized: $result")
        } *> ZIO.service[Transactor]
      }
  end DataSourceProvider
end PostgresTestContainer
