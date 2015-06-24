package funnel
package chemist

/**
 * Chemist is typically consuming event notificaitons about the state of
 * the external world (e.g. from messages queues, or a 3rd party stream),
 * and subsequetnly oft is the case where one will want to specifically
 * only have a single chemist node in the cluster actually consuming that
 * mutable, external resource. Likewise, assigning work to Flask instances
 * (critically important to avoid duplication of work assignments) should
 * only be conducted by a single node at a time.
 *
 * Another important use case is upgrades and system evolution. Over
 * time it is likley that you will want to migrate from one chemist to
 * a newer version, and as such, you only want one "cluster" of chemists
 * to be controlling your fleet of Flasks.
 *
 * With these use cases in mind, Chemist has the concept of an election
 * strategy, which provides a course grained mechinhism for deciding if
 * this machine / cluster is the leader or not.
 */
trait ElectionStrategy {
  def discovery: Discovery
  def leader: Option[Location]
  def isLeader(l: Location): Boolean
}

/**
 * A common use case is to run a single Chemist under process supervision
 * and is the mode in which most of the testing is done (e.g. the integration
 * test module). In these scenarios it make sense to use a singleton chemist
 * and always have a lone nominee that gets elected in perpetuity.
 */
case class ForegoneConclusion(
  discovery: Discovery,
  nominee: Location
) extends ElectionStrategy {
  val leader: Option[Location] = Some(nominee)
  def isLeader(l: Location): Boolean = true
}
