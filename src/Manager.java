import java.util.List;

@MongoCollection
public class Manager extends Employee {
	@MongoField int parkingSpot;
    @MongoField List<Employee> directReports; // must avoid cyclic pickling
}
