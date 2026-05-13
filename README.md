# WiseWays Machine Engine — Java / Spring Boot

Full conversion of `machine.py` (Flask + pandas + scikit-learn) to
**Java 17 + Spring Boot 3**.

---

## ML note

`machine.py` trained a `RandomForestRegressor` but the live `/recommend` route
**never called `model.predict()`** — it sorted colleges purely by rank difference
with an optional branch bonus.  This Java port replicates that exact logic.

If you want the ML-backed prediction from `model.py`, add **Smile**:

```xml
<dependency>
    <groupId>com.github.haifengl</groupId>
    <artifactId>smile-core</artifactId>
    <version>3.1.1</version>
</dependency>
```

…and call `smile.regression.RandomForest.fit(formula, dataFrame)` inside
`DataService.initData()`.

---

## Project structure

```
wiseway-java/
├── pom.xml
└── src/main/
    ├── java/com/wiseways/
    │   ├── MachineApplication.java          ← Spring Boot entry point
    │   ├── config/
    │   │   ├── CorsConfig.java              ← replaces flask_cors CORS(app)
    │   │   └── RestTemplateConfig.java      ← HTTP client bean
    │   ├── controller/
    │   │   └── ApiController.java           ← GET /  POST /ask  POST /recommend
    │   ├── model/
    │   │   ├── CollegeEntry.java            ← internal row (≈ df row)
    │   │   ├── CollegeResult.java           ← /recommend response element
    │   │   └── RecommendRequest.java        ← /recommend request body
    │   └── service/
    │       ├── AiService.java               ← NVIDIA API client
    │       └── DataService.java             ← CSV loading + recommendation
    └── resources/
        └── application.properties
```

---

## Running

### Prerequisites
- Java 17+
- Maven 3.8+
- CSV files (`uptac2.csv`, `Book2.csv`, …) in the **same directory** you run from

### Build & run

```bash
# Build fat-jar
mvn clean package -DskipTests

# Run  (CSV files must be in the current directory)
java -jar target/machine-engine-1.0.0.jar

# — or run directly without packaging —
mvn spring-boot:run
```

Server starts on **http://localhost:5000** — the same port as the Python version.

---

## API

```
GET  /

POST /ask
  Body   : { "query": "What is JEE Advanced?" }
  Returns: { "response": "..." }

POST /recommend
  Body:
  {
    "rank"        : 5000,
    "categoryRank": "",
    "branch"      : "Computer Science and Engineering",
    "area"        : "",
    "budget"      : "any",
    "counselling" : "any"
  }
  Returns:
  {
    "colleges": [
      {
        "college"      : "...",
        "branch"       : "...",
        "closing_rank" : 4823,
        "match_score"  : 91,
        "city"         : "...",
        "avg_package"  : "12.5 LPA",
        "fees"         : "8.2 Lakhs"
      },
      ...
    ]
  }
```

---

## Configuration

`src/main/resources/application.properties`

```properties
server.port=5000
nvidia.api.base-url=https://integrate.api.nvidia.com/v1
nvidia.api.key=YOUR_API_KEY_HERE
nvidia.api.model=meta/llama-3.1-8b-instruct
csv.primary=uptac2.csv
```
