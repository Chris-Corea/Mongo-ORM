import java.util.*;

@MongoCollection
public class Employee {
    @MongoField String name;
    @MongoField double yearlySalary; // must be double not float
    @MongoField Employee manager; // must avoid cyclic pickling
    @MongoField List<Project> projects; // must avoid cyclic pickling
        int ignoredField;
}
