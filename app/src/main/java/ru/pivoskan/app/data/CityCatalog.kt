package ru.pivoskan.app.data

/** Geographic and chain names only. All prices come from remote official catalogs. */
object CityCatalog {
    val cities = listOf(
        City("msk", "Москва"),
        City("balashikha", "Балашиха", "Московская область"),
        City("khimki", "Химки", "Московская область"),
        City("mytishchi", "Мытищи", "Московская область"),
        City("korolev", "Королёв", "Московская область"),
        City("lyubertsy", "Люберцы", "Московская область"),
        City("odintsovo", "Одинцово", "Московская область"),
        City("krasnogorsk", "Красногорск", "Московская область"),
        City("reutov", "Реутов", "Московская область"),
        City("podolsk", "Подольск", "Московская область"),
        City("dolgoprudny", "Долгопрудный", "Московская область"),
        City("pushkino", "Пушкино", "Московская область"),
        City("shchyolkovo", "Щёлково", "Московская область"),
        City("spb", "Санкт-Петербург"), City("krd", "Краснодар"),
        City("ekb", "Екатеринбург"), City("nsk", "Новосибирск"),
        City("kzn", "Казань"), City("nnv", "Нижний Новгород")
    )
    val stores = listOf(
        Store("magnit", "Магнит", "Магнит", 0xFFD64545.toInt()),
        Store("dixy", "Дикси", "Дикси", 0xFF1F8A70.toInt()),
        Store("pyaterochka", "Пятёрочка", "Пятёрочка", 0xFF5BBF3A.toInt()),
        Store("fixprice", "Fix Price", "Fix Price", 0xFF4B7BEC.toInt())
    )
    fun city(id: String): City? = cities.firstOrNull { it.id == id }
    fun store(id: String): Store? = stores.firstOrNull { it.id == id }

    /** A selected source address, never the app's city label, establishes local provenance. */
    fun addressMatches(city: City, address: String): Boolean {
        val text = address.lowercase(java.util.Locale.ROOT).replace('ё', 'е').replace(Regex("[\\s\\p{Z}]+"), " ")
        val name = city.name.lowercase(java.util.Locale.ROOT).replace('ё', 'е')
        val cityPattern = Regex("(?<![\\p{L}\\p{M}\\p{N}_])" + Regex.escape(name) + "(?![\\p{L}\\p{M}\\p{N}_])")
        return cityPattern.containsMatchIn(text) && (city.region != "Московская область" ||
            Regex("московск[а-я]*[., ]+обл").containsMatchIn(text))
    }
}
