package com.example.mealplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.app.Application

// ---------- Data Models & Persistence ----------

data class Ingredient(
    val name: String,
    var quantity: Double,
    val unit: String
)

@Entity(tableName = "recipes")
@TypeConverters(Converters::class)
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val ingredients: List<Ingredient>,
    val tags: String? = null
)

class Converters {
    @TypeConverter
    fun fromIngredients(value: String): List<Ingredient> {
        val listType = object : TypeToken<List<Ingredient>>() {}.type
        return Gson().fromJson(value, listType)
    }
    @TypeConverter
    fun toIngredients(list: List<Ingredient>): String = Gson().toJson(list)
}

@Dao
interface RecipeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: RecipeEntity): Long

    @Query("SELECT * FROM recipes ORDER BY name ASC")
    suspend fun getAll(): List<RecipeEntity>

    @Delete
    suspend fun delete(recipe: RecipeEntity)
}

@Database(entities = [RecipeEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(app: Application): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(app, AppDatabase::class.java, "meal_planner_db").build().also { INSTANCE = it }
            }
    }
}

// ---------- Repository & ViewModel ----------

class RecipeRepository(private val dao: RecipeDao) {
    suspend fun addRecipe(recipe: RecipeEntity) = dao.insert(recipe)
    suspend fun getAllRecipes(): List<RecipeEntity> = dao.getAll()
    fun generateMealPlan(pool: List<RecipeEntity>, days: Int): List<RecipeEntity> = pool.shuffled().take(days)
    fun aggregateIngredients(plan: List<RecipeEntity>): List<Ingredient> {
        val map = mutableMapOf<Pair<String, String>, Double>()
        plan.flatMap { it.ingredients }.forEach { ingr ->
            val key = ingr.name to ingr.unit
            map[key] = (map[key] ?: 0.0) + ingr.quantity
        }
        return map.map { Ingredient(it.key.first, it.value, it.key.second) }
    }
}

class RecipeViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = RecipeRepository(AppDatabase.getInstance(application).recipeDao())
    private val _recipes = MutableLiveData<List<RecipeEntity>>(emptyList())
    val recipes: LiveData<List<RecipeEntity>> = _recipes
    private val _mealPlan = MutableLiveData<List<RecipeEntity>>(emptyList())
    val mealPlan: LiveData<List<RecipeEntity>> = _mealPlan
    private val _shoppingList = MutableLiveData<List<Ingredient>>(emptyList())
    val shoppingList: LiveData<List<Ingredient>> = _shoppingList

    init {
        viewModelScope.launch { _recipes.value = repo.getAllRecipes() }
    }

    fun addRecipe(name: String, ingredients: List<Ingredient>, tags: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.addRecipe(RecipeEntity(name = name, ingredients = ingredients, tags = tags))
            withContext(Dispatchers.Main) { _recipes.value = repo.getAllRecipes() }
        }
    }

    fun createMealPlan(days: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val plan = repo.generateMealPlan(repo.getAllRecipes(), days)
            val list = repo.aggregateIngredients(plan)
            withContext(Dispatchers.Main) {
                _mealPlan.value = plan
                _shoppingList.value = list
            }
        }
    }

    fun updateShoppingList(updated: List<Ingredient>) {
        _shoppingList.value = updated
    }
}

// ---------- UI Layer (Jetpack Compose) ----------

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFD700),   // Dorado
    onPrimary = Color.Black,
    background = Color.Black,
    surface = Color.DarkGray,
    onSurface = Color.LightGray
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MealPlannerApp() }
    }
}

@Composable
fun MealPlannerApp(viewModel: RecipeViewModel = viewModel()) {
    var screen by remember { mutableStateOf("plan") }
    MaterialTheme(colorScheme = DarkColorScheme) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.DarkGray) {
                    NavigationBarItem(
                        selected = screen == "plan",
                        onClick = { screen = "plan" },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("Plan") }
                    )
                    NavigationBarItem(
                        selected = screen == "shop",
                        onClick = { screen = "shop" },
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                        label = { Text("Compras") }
                    )
                }
            }
        ) { inner ->
            Box(modifier = Modifier.padding(inner).fillMaxSize()) {
                if (screen == "plan") {
                    MainScreen(
                        recipes = viewModel.recipes.observeAsState(emptyList()).value,
                        mealPlan = viewModel.mealPlan.observeAsState(emptyList()).value,
                        onAddRecipe = { name, qty, unit, tags ->
                            viewModel.addRecipe(name, listOf(Ingredient(name, qty, unit)), tags)
                        },
                        onGeneratePlan = { days -> viewModel.createMealPlan(days) }
                    )
                } else {
                    ShoppingListScreen(
                        shoppingList = viewModel.shoppingList.observeAsState(emptyList()).value,
                        onListChange = { viewModel.updateShoppingList(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    recipes: List<RecipeEntity>,
    mealPlan: List<RecipeEntity>,
    onAddRecipe: (String, Double, String, String?) -> Unit,
    onGeneratePlan: (Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var daysText by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        /* Alta de Receta, Generación de Plan, Listados similares al código anterior */
    }
}

@Composable
fun ShoppingListScreen(
    shoppingList: List<Ingredient>,
    onListChange: (List<Ingredient>) -> Unit
) {\n    var list by remember { mutableStateOf(shoppingList.toMutableList()) }
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Lista de Compras", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            itemsIndexed(list) { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(item.name, modifier = Modifier.weight(2f))
                    OutlinedTextField(
                        value = item.quantity.toString(),
                        onValueChange = {
                            val qty = it.toDoubleOrNull() ?: item.quantity
                            list[index] = item.copy(quantity = qty)
                            onListChange(list)
                        },
                        modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(item.unit, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
