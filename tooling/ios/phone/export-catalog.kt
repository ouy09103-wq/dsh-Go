// Run with the repository's unchanged PhoneToolCatalog.kt and Json.kt.
import io.github.ouy09103.dsh_go_root.phone.PhoneToolCatalog
import io.github.ouy09103.dsh_go_root.phone.Json
fun main() { print(Json.write(PhoneToolCatalog.listJson())) }
