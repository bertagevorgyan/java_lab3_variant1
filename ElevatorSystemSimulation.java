import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;


// возможные статусы лифта
enum ElevatorStatus {
    IDLE("IDLE"), // лифт стоит на этаже, без движения
    MOVING("MOVING"), // лифт движется 
    DOORS_OPEN("DOORS OPEN"); // лифт остановился, двери открыты

    private final String description;

    ElevatorStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

// направления движения 
enum Direction {
    UP("UP"), // движение вверх
    DOWN("DOWN"), // движение вниз
    NONE("NONE"); // нет направления (лифт стоит)

    private final String description;

    Direction(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}


//класс для внутреннего запроса пассажира (выбор этажа в лифте)
class InternalRequest {
    private final int targetFloor; //целевой этаж
    private final long timestamp; //время создания запроса

    public InternalRequest(int targetFloor) {
        this.targetFloor = targetFloor;
        this.timestamp = System.currentTimeMillis();
    }

    public int getTargetFloor() {
        return targetFloor;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Internal request to floor " + targetFloor;
    }
}

//класс для внешнего запроса (вызов лифта с этажа)
class ExternalRequest {
    private final int floor; //этаж, с которого вызывают лифт
    private final Direction direction; //направление, в котором хочет ехать пассажир
    private final long timestamp; //время создания запроса

    public ExternalRequest(int floor, Direction direction) {
        this.floor = floor;
        this.direction = direction;
        this.timestamp = System.currentTimeMillis();
    }

    public int getFloor() {
        return floor;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "External call from floor " + floor + " going " + direction;
    }
}

/**
 * класс Elevator представляет один лифт в системе.
 * каждый лифт работает в отдельном потоке.
 * лифт имеет состояние, обрабатывает запросы и перемещается между этажами.
 */
class Elevator implements Runnable {
    private final int id; //уникальный идентификатор лифта
    private int currentFloor; 
    private Direction direction; 
    private ElevatorStatus status; 
    private final int maxFloor; 
    private final int minFloor = 1;  

    //очередь внутренних запросов (выбор этажей внутри лифта)
    private final BlockingQueue<InternalRequest> internalRequests;

    //множество этажей, на которых нужно остановиться
    private final Set<Integer> pendingFloors;

    //примитивы синхронизации для потокобезопасности
    private final ReentrantLock lock; // Замок для синхронизации
    private final Condition condition; // Условие для ожидания работы

    private volatile boolean running = true; //флаг работы потока лифта
    private int passengerCount = 0; 
    private final int maxCapacity = 10; //максимальная вместимость лифта

    private final Logger logger = Logger.getInstance(); //логгер для записи событий

    /**
     * конструктор лифта
     * 
     * @param id       - идентификатор лифта
     * @param maxFloor - максимальный этаж в здании
     */
    public Elevator(int id, int maxFloor) {
        this.id = id;
        this.currentFloor = 1; 
        this.direction = Direction.NONE;
        this.status = ElevatorStatus.IDLE;
        this.maxFloor = maxFloor;
        //используем потокобезопасные коллекции
        this.internalRequests = new LinkedBlockingQueue<>();
        this.pendingFloors = new HashSet<>();
        this.lock = new ReentrantLock();
        this.condition = lock.newCondition();
    }


    public int getId() {
        return id;
    }

    public int getCurrentFloor() {
        lock.lock(); //блокируем для безопасного доступа из других потоков
        try {
            return currentFloor;
        } finally {
            lock.unlock(); //всегда разблокируем в finally
        }
    }

    public Direction getDirection() {
        lock.lock();
        try {
            return direction;
        } finally {
            lock.unlock();
        }
    }

    public ElevatorStatus getStatus() {
        lock.lock();
        try {
            return status;
        } finally {
            lock.unlock();
        }
    }

    public int getPassengerCount() {
        return passengerCount;
    }

    public boolean canAcceptPassenger() {
        return passengerCount < maxCapacity;
    }


    /**
     * добавление внешнего запроса (вызов лифта с этажа)
     * 
     * @param floor - этаж вызова
     * @param dir   - направление движения
     * @return true если запрос принят
     */
    public boolean addExternalRequest(int floor, Direction dir) {
        lock.lock(); //захватываем замок для синхронизации
        try {
            //если лифт уже должен остановиться на этом этаже, возвращаем true
            if (pendingFloors.contains(floor)) {
                return true;
            }

            //добавляем этаж в список ожидаемых остановок
            pendingFloors.add(floor);
            logger.logElevator(id, "Accepted external request for floor " + floor +
                    " (direction: " + dir + ")");

            //если лифт стоит без направления, задаем направление
            if (direction == Direction.NONE) {
                direction = (floor > currentFloor) ? Direction.UP : Direction.DOWN;
                status = ElevatorStatus.MOVING;
                condition.signalAll(); //будим поток лифта, если он ждет
            }

            return true;
        } finally {
            lock.unlock(); //всегда освобождаем замок
        }
    }

    /**
     * добавление внутреннего запроса (выбор этажа в лифте)
     * 
     * @param targetFloor - целевой этаж
     * @return true если запрос принят
     */
    public boolean addInternalRequest(int targetFloor) {
        //проверка корректности этажа
        if (targetFloor < minFloor || targetFloor > maxFloor) {
            logger.logElevator(id, "ERROR: Invalid floor " + targetFloor);
            return false;
        }

        lock.lock();
        try {
            //проверка вместимости лифта
            if (!canAcceptPassenger()) {
                logger.logElevator(id, "FULL: Elevator full! Max 10 passengers.");
                return false;
            }

            //создаем и добавляем запрос
            InternalRequest request = new InternalRequest(targetFloor);
            internalRequests.offer(request);
            pendingFloors.add(targetFloor);

            //увеличиваем счетчик пассажиров
            passengerCount++;
            logger.logElevator(id, "Passenger entered. Target floor " + targetFloor +
                    " (passengers: " + passengerCount + ")");

            //если лифт стоит, начинаем движение
            if (direction == Direction.NONE) {
                direction = (targetFloor > currentFloor) ? Direction.UP : Direction.DOWN;
                status = ElevatorStatus.MOVING;
                condition.signalAll();
            }

            return true;
        } finally {
            lock.unlock();
        }
    }


    /**
     *пассажир выходит из лифта
     */
    public void passengerExits() {
        lock.lock();
        try {
            if (passengerCount > 0) {
                passengerCount--;
                logger.logElevator(id, "Passenger exited. Remaining: " + passengerCount);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * остановка работы лифта (завершение потока)
     */
    public void stop() {
        running = false; //устанавливаем флаг завершения
        lock.lock();
        try {
            condition.signalAll(); //будим поток, если он ждет
        } finally {
            lock.unlock();
        }
    }

    /**
     * перемещение лифта на один этаж
     */
    private void move() {
        lock.lock();
        try {
            if (direction == Direction.UP) {
                currentFloor++;
                //если достигли верхнего этажа, меняем направление
                if (currentFloor >= maxFloor) {
                    direction = Direction.DOWN;
                }
            } else if (direction == Direction.DOWN) {
                currentFloor--;
                //если достигли нижнего этажа, меняем направление
                if (currentFloor <= minFloor) {
                    direction = Direction.UP;
                }
            }

            logger.logElevator(id, "Moved to floor " + currentFloor + " (" + direction + ")");
        } finally {
            lock.unlock();
        }
    }

    /**
     * обработка текущего этажа (остановка, если нужно)
     */
    private void processFloor() {
        lock.lock();
        try {
            //проверяем, нужно ли останавливаться на текущем этаже
            if (pendingFloors.contains(currentFloor)) {
                status = ElevatorStatus.DOORS_OPEN;
                logger.logElevator(id, "--------------------------------------------------");
                logger.logElevator(id, "STOPPED at floor " + currentFloor);
                logger.logElevator(id, "Doors opening...");

                // Симуляция открытия дверей (задержка)
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Удаляем этаж из списка ожидаемых остановок
                pendingFloors.remove(currentFloor);

                // Случайно некоторые пассажиры выходят
                if (passengerCount > 0 && new Random().nextBoolean()) {
                    passengerExits();
                }

                logger.logElevator(id, "Doors closing...");

                // Симуляция закрытия дверей
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Если больше нет запросов, переходим в режим ожидания
                if (pendingFloors.isEmpty() && internalRequests.isEmpty()) {
                    status = ElevatorStatus.IDLE;
                    direction = Direction.NONE;
                    logger.logElevator(id, "IDLE at floor " + currentFloor);
                } else {
                    status = ElevatorStatus.MOVING;
                }
                logger.logElevator(id, "--------------------------------------------------");
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Основной метод работы лифта (запускается в отдельном потоке)
     */
    @Override
    public void run() {
        logger.logSystem("Elevator #" + (id + 1) + " STARTED");

        // Основной цикл работы лифта
        while (running) {
            lock.lock();
            try {
                // Ожидание, пока есть работа для лифта
                while (running && pendingFloors.isEmpty() && internalRequests.isEmpty()) {
                    try {
                        condition.await(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                // Проверяем, нужно ли завершать работу
                if (!running) {
                    break;
                }

                // Если лифт движется, выполняем шаг
                if (status == ElevatorStatus.MOVING) {
                    move(); // Перемещаемся на следующий этаж
                    processFloor(); // Обрабатываем текущий этаж
                    processInternalRequests(); // Обрабатываем внутренние запросы
                }

            } finally {
                lock.unlock();
            }

            // Пауза между движениями (симуляция времени перемещения)
            try {
                Thread.sleep(1000); // 1 секунда на движение между этажами
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.logSystem("Elevator #" + (id + 1) + " STOPPED");
    }

    /**
     * Обработка внутренних запросов (удаление выполненных)
     */
    private void processInternalRequests() {
        lock.lock();
        try {
            Iterator<InternalRequest> iterator = internalRequests.iterator();
            while (iterator.hasNext()) {
                InternalRequest request = iterator.next();
                // Удаляем запрос, если мы его уже выполнили
                if ((direction == Direction.UP && request.getTargetFloor() <= currentFloor) ||
                        (direction == Direction.DOWN && request.getTargetFloor() >= currentFloor)) {
                    iterator.remove();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Расчет "стоимости" назначения запроса этому лифту
     * Алгоритм выбора лучшего лифта для запроса
     * 
     * @param floor - этаж вызова
     * @param dir   - направление вызова
     * @return стоимость (меньше = лучше)
     */
    public int calculateCost(int floor, Direction dir) {
        lock.lock();
        try {
            // Если лифт полный, возвращаем максимальную стоимость
            if (!canAcceptPassenger()) {
                return Integer.MAX_VALUE;
            }

            int cost = 0;

            // Сценарий 1: Лифт стоит
            if (status == ElevatorStatus.IDLE) {
                cost = Math.abs(currentFloor - floor);
            }
            // Сценарий 2: Лифт движется в том же направлении
            else if (direction == dir) {
                // Если запрос по пути движения
                if ((dir == Direction.UP && floor >= currentFloor) ||
                        (dir == Direction.DOWN && floor <= currentFloor)) {
                    cost = Math.abs(currentFloor - floor);
                } else {
                    // Лифту нужно доехать до конца и вернуться
                    cost = Math.abs(currentFloor - (dir == Direction.UP ? maxFloor : minFloor)) +
                            Math.abs((dir == Direction.UP ? maxFloor : minFloor) - floor);
                }
            }
            // Сценарий 3: Лифт движется в противоположном направлении
            else {
                cost = Math.abs(currentFloor - (direction == Direction.UP ? maxFloor : minFloor)) +
                        Math.abs((direction == Direction.UP ? maxFloor : minFloor) - floor);
            }

            // Учитываем количество уже запланированных остановок
            cost += pendingFloors.size() * 2;

            return cost;
        } finally {
            lock.unlock();
        }
    }
}


/**
 * Класс Dispatcher отвечает за распределение запросов между лифтами.
 * Работает в отдельном потоке и выбирает оптимальный лифт для каждого запроса.
 */
class Dispatcher implements Runnable {
    private final List<Elevator> elevators; // Список всех лифтов в системе
    private final BlockingQueue<ExternalRequest> externalRequests; // Очередь внешних запросов
    private volatile boolean running = true; // Флаг работы потока диспетчера
    private final Logger logger = Logger.getInstance();
    private final int maxFloor; 

    public Dispatcher(List<Elevator> elevators, int maxFloor) {
        this.elevators = elevators;
        this.externalRequests = new LinkedBlockingQueue<>();
        this.maxFloor = maxFloor;
    }

    /**
     * Добавление внешнего запроса в очередь
     */
    public void addExternalRequest(int floor, Direction direction) {
        // Проверка корректности этажа
        if (floor < 1 || floor > maxFloor) {
            logger.logError("Invalid floor number: " + floor);
            return;
        }

        ExternalRequest request = new ExternalRequest(floor, direction);
        externalRequests.offer(request);
        logger.logDispatcher("Received call: " + request);
    }

    public void stop() {
        running = false;
    }

    /**
     * Назначение запроса наилучшему лифту
     */
    private void assignRequest(ExternalRequest request) {
        Elevator bestElevator = null;
        int minCost = Integer.MAX_VALUE;

        // Поиск лифта с минимальной стоимостью
        for (Elevator elevator : elevators) {
            int cost = elevator.calculateCost(request.getFloor(), request.getDirection());
            if (cost < minCost) {
                minCost = cost;
                bestElevator = elevator;
            }
        }

        // Назначение запроса лучшему лифту
        if (bestElevator != null) {
            bestElevator.addExternalRequest(request.getFloor(), request.getDirection());
            logger.logDispatcher("Assigned to Elevator #" + (bestElevator.getId() + 1) +
                    " for call from floor " + request.getFloor());
        } else {
            logger.logError("No available elevators for call from floor " + request.getFloor());
        }
    }

    /**
     * Основной метод работы диспетчера
     */
    @Override
    public void run() {
        logger.logSystem("Dispatcher STARTED");

        // Основной цикл диспетчера
        while (running) {
            try {
                // Ожидаем запрос с таймаутом 100 мс
                ExternalRequest request = externalRequests.poll(100, TimeUnit.MILLISECONDS);

                // Если есть запрос, обрабатываем его
                if (request != null) {
                    assignRequest(request);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.logSystem("Dispatcher STOPPED");
    }

    /**
     * Прямой вызов лифта (для внутренних запросов)
     */
    public void callElevator(int elevatorId, int targetFloor) {
        if (elevatorId >= 0 && elevatorId < elevators.size()) {
            elevators.get(elevatorId).addInternalRequest(targetFloor);
        }
    }
}


/**
 * Класс Logger обеспечивает потокобезопасное логирование событий.
 * Использует паттерн Singleton (одиночка).
 */
class Logger {
    private static Logger instance; // Единственный экземпляр логгера
    private final DateTimeFormatter timeFormatter; // Формат времени
    private final Lock logLock; // Замок для синхронизации вывода

    private Logger() {
        this.timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        this.logLock = new ReentrantLock();
    }

    /**
     * Получение экземпляра логгера (Singleton)
     */
    public static synchronized Logger getInstance() {
        if (instance == null) {
            instance = new Logger();
        }
        return instance;
    }

    /**
     * Базовый метод логирования
     */
    private void log(String type, String message) {
        logLock.lock(); // Синхронизация вывода в консоль
        try {
            String time = LocalTime.now().format(timeFormatter);
            System.out.printf("%s [%-10s] %s%n", time, type, message);
        } finally {
            logLock.unlock();
        }
    }

    // Специализированные методы логирования для разных типов событий

    public void logSystem(String message) {
        log("SYSTEM", message);
    }

    public void logDispatcher(String message) {
        log("DISPATCHER", message);
    }

    public void logElevator(int id, String message) {
        log("ELEVATOR #" + (id + 1), message);
    }

    public void logError(String message) {
        log("ERROR", message);
    }

    public void logInfo(String message) {
        log("INFO", message);
    }
}

/**
 * Основной класс системы управления лифтами.
 * Координирует работу всех компонентов системы.
 */
public class ElevatorSystemSimulation {
    private final List<Elevator> elevators; // Все лифты в системе
    private final Dispatcher dispatcher; // Диспетчер
    private final Thread dispatcherThread; // Поток диспетчера
    private final List<Thread> elevatorThreads; // Потоки всех лифтов
    private final int maxFloor; // Максимальный этаж
    private final Random random; // Генератор случайных чисел
    private final Logger logger; // Логгер
    private volatile boolean running = false; // Флаг работы системы

    /**
     * Конструктор системы
     * 
     * @param numElevators - количество лифтов
     * @param maxFloor     - количество этажей
     */
    public ElevatorSystemSimulation(int numElevators, int maxFloor) {
        this.maxFloor = maxFloor;
        this.elevators = new ArrayList<>();
        this.elevatorThreads = new ArrayList<>();
        this.random = new Random();
        this.logger = Logger.getInstance();

        // Создание лифтов
        for (int i = 0; i < numElevators; i++) {
            Elevator elevator = new Elevator(i, maxFloor);
            elevators.add(elevator);
        }

        // Создание диспетчера
        dispatcher = new Dispatcher(elevators, maxFloor);
        dispatcherThread = new Thread(dispatcher, "Dispatcher");
    }

    /**
     * Запуск всей системы
     */
    public void start() {
        if (running)
            return;

        running = true;
        logger.logSystem("==========================================");
        logger.logSystem("ELEVATOR SYSTEM STARTING");
        logger.logSystem("Elevators: " + elevators.size());
        logger.logSystem("Floors: " + maxFloor);
        logger.logSystem("==========================================");

        // Запуск потоков лифтов
        for (int i = 0; i < elevators.size(); i++) {
            Thread thread = new Thread(elevators.get(i), "Elevator-" + i);
            elevatorThreads.add(thread);
            thread.start();
        }

        // Запуск потока диспетчера
        dispatcherThread.start();

        logger.logSystem("System started and ready");
    }

    /**
     * Корректная остановка системы
     */
    public void stop() {
        if (!running)
            return;

        running = false;
        logger.logSystem("==========================================");
        logger.logSystem("ELEVATOR SYSTEM STOPPING");
        logger.logSystem("==========================================");

        // Остановка диспетчера
        dispatcher.stop();

        // Остановка всех лифтов
        for (Elevator elevator : elevators) {
            elevator.stop();
        }

        // Ожидание завершения всех потоков
        try {
            dispatcherThread.join(2000);
            for (Thread thread : elevatorThreads) {
                thread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.logSystem("System stopped correctly");
    }

    /**
     * Вызов лифта с этажа
     */
    public void callElevator(int floor, Direction direction) {
        if (!running) {
            logger.logError("System is not running");
            return;
        }

        if (floor < 1 || floor > maxFloor) {
            logger.logError("Invalid floor: " + floor + " (must be 1-" + maxFloor + ")");
            return;
        }

        dispatcher.addExternalRequest(floor, direction);
    }

    /**
     * Выбор этажа в лифте
     */
    public void selectFloor(int elevatorId, int targetFloor) {
        if (!running) {
            logger.logError("System is not running");
            return;
        }

        if (elevatorId < 0 || elevatorId >= elevators.size()) {
            logger.logError("Invalid elevator ID: " + elevatorId +
                    " (must be 0-" + (elevators.size() - 1) + ")");
            return;
        }

        dispatcher.callElevator(elevatorId, targetFloor);
    }

    /**
     * Генерация случайных запросов для тестирования
     */
    public void generateRandomRequests(int numRequests) {
        logger.logInfo("Generating " + numRequests + " random requests...");

        new Thread(() -> {
            for (int i = 0; i < numRequests; i++) {
                int delay = random.nextInt(3000) + 500;
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (!running)
                    break;

                // 70% внешних запросов, 30% внутренних
                if (random.nextDouble() < 0.7) {
                    int floor = random.nextInt(maxFloor) + 1;
                    Direction direction = random.nextBoolean() ? Direction.UP : Direction.DOWN;
                    callElevator(floor, direction);
                } else {
                    int elevatorId = random.nextInt(elevators.size());
                    int targetFloor = random.nextInt(maxFloor) + 1;
                    selectFloor(elevatorId, targetFloor);
                }
            }
        }).start();
    }

    /**
     * Отображение текущего статуса системы
     */
    public void displayStatus() {
        logger.logSystem("==========================================");
        logger.logSystem("SYSTEM STATUS");
        logger.logSystem("==========================================");

        for (Elevator elevator : elevators) {
            String statusText = elevator.getStatus().getDescription();
            String directionText = elevator.getDirection().getDescription();

            logger.logInfo(String.format("Elevator #%d: Floor %2d | %s | %s | Passengers: %d/10",
                    elevator.getId() + 1,
                    elevator.getCurrentFloor(),
                    statusText,
                    directionText,
                    elevator.getPassengerCount()));
        }
        logger.logSystem("==========================================");
    }

    /**
     * Точка входа в программу
     */
    public static void main(String[] args) {
        // Конфигурация системы
        int numElevators = 4; // Количество лифтов
        int numFloors = 10; // Количество этажей
        int simulationTime = 45; // Время симуляции в секундах

        Logger logger = Logger.getInstance();

        logger.logSystem("==========================================");
        logger.logSystem("ELEVATOR SYSTEM SIMULATION");
        logger.logSystem("==========================================");
        logger.logInfo("Configuration: " + numElevators + " elevators, " + numFloors + " floors");
        logger.logInfo("Simulation time: " + simulationTime + " seconds");

        // Создание системы
        ElevatorSystemSimulation system = new ElevatorSystemSimulation(numElevators, numFloors);

        // Запуск системы
        system.start();

        // Пауза для инициализации
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Демонстрационные запросы
        logger.logInfo("======== DEMONSTRATION REQUESTS ========");

        logger.logInfo("1. Call elevator to floor 5 going UP");
        system.callElevator(5, Direction.UP);

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
        }

        logger.logInfo("2. In elevator #1 select floor 8");
        system.selectFloor(0, 8);

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
        }

        logger.logInfo("3. Call elevator to floor 9 going DOWN");
        system.callElevator(9, Direction.DOWN);

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
        }

        logger.logInfo("4. In elevator #2 select floor 1");
        system.selectFloor(1, 1);

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
        }

        logger.logInfo("5. Call elevator to floor 3 going UP");
        system.callElevator(3, Direction.UP);

        // Показ статуса
        system.displayStatus();

        // Генерация случайных запросов
        logger.logInfo("======== RANDOM REQUESTS ========");
        system.generateRandomRequests(8);

        // Работа системы заданное время
        try {
            Thread.sleep(simulationTime * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Финальный статус
        system.displayStatus();

        // Остановка системы
        system.stop();

        logger.logSystem("==========================================");
        logger.logSystem("SIMULATION COMPLETED");
        logger.logSystem("==========================================");
    }

}
