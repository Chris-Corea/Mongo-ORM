import java.util.Date;


@MongoCollection
public class Project {
	@MongoField String name;
    @MongoField Date begin;
    @MongoField Date end;
}
