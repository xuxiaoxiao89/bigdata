package com.datastax;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.FallthroughRetryPolicy;
import com.datastax.driver.core.querybuilder.QueryBuilder;

import java.util.List;

/**
 * Created by zhengqh on 15/10/16.
 */
public class CassandraClientTest {

    static CassandraClient client = new CassandraClient();

    //Velocity原先的查询方式
    static String velocity = "select * from velocity where attribute=? and partner_code=? and app_name=? and type=?";
    //给Velocity加上order by和limit
    static String velocityOrder = "select * from velocity where attribute=? and partner_code=? and app_name=? and type=? order by partner_code desc,app_name desc,type desc,timestamp desc limit 1000";
    //拆表之后的查询
    static String velocity_app = "select * from velocity_app where attribute=? and partner_code=? and app_name=? and type=? order by timestamp desc limit 1000";
    static String velocity_partner = "select * from velocity_partner where attribute=? and partner_code=? order by timestamp desc limit 1000";
    static String velocity_global = "select * from velocity_global where attribute=? order by timestamp desc limit 1000";

    static {
        client.setKeyspace("forseti");
        client.setFetchSize(2);
        client.setLocalDc("DC1");
        client.setAgentHostList("192.168.6.52,192.168.6.53");
        client.init();
    }

    public static void main(String[] args) {
        //insertByClient();
        //insertUsingTTL();

        velocityDataSyncAll();

        client.close();
        System.exit(0);
    }

    public static void truncateVelocity3(){
        truncate("velocity_app");
        truncate("velocity_partner");
        truncate("velocity_global");
    }
    public static void truncate(String tbl){
        PreparedStatement truncate = client.getSession().prepare("truncate " + tbl);
        client.execute(truncate);
    }

    //TODO: 线上环境显然不能直接查询全量数据, 可以采用分页? 但是C*的分页使用token方式实现. 有点麻烦
    //16000,2.5min
    public static void velocityDataSyncAll(){
        PreparedStatement pstmtSelectAll = client.getSession().prepare("select * from velocity");
        pstmtSelectAll.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);
        List<Row> rows = client.getAll(pstmtSelectAll);

        truncateVelocity3();

        long start = System.currentTimeMillis();
        PreparedStatement pstmtInsert = client.getSession().prepare(
                "BEGIN BATCH" +
                        " insert into velocity_app(attribute, partner_code, app_name, type, timestamp, event, sequence_id) values(?, ?, ?, ?, ?, ?, ?);" +
                        " insert into velocity_partner(attribute, partner_code, app_name, type, timestamp, event, sequence_id) values(?, ?, ?, ?, ?, ?, ?);" +
                        " insert into velocity_global(attribute, partner_code, app_name, type, timestamp, event, sequence_id) values(?, ?, ?, ?, ?, ?, ?);" +
                        "APPLY BATCH");
        pstmtInsert.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);

        for(Row row : rows){
            String attribute = row.getString("attribute");
            String partner_code = row.getString("partner_code");
            String app_name = row.getString("app_name");
            String type = row.getString("type");
            Long timestamp = row.getLong("timestamp");
            String event = row.getString("event");
            String sequence_id = row.getString("sequence_id");

            client.getSession().execute(pstmtInsert.bind(
                    attribute, partner_code, app_name, type, timestamp, event, sequence_id,
                    attribute, partner_code, app_name, type, timestamp, event, sequence_id,
                    attribute, partner_code, app_name, type, timestamp, event, sequence_id
            ));
        }
        long end = System.currentTimeMillis();

        truncateVelocity3();

        long start2 = System.currentTimeMillis();

        PreparedStatement pstmtInsert1 = client.getSession().prepare("insert into velocity_app(attribute, partner_code, app_name, type, timestamp, event, sequence_id) values(?, ?, ?, ?, ?, ?, ?)");
        PreparedStatement pstmtInsert2 = client.getSession().prepare("insert into velocity_partner(attribute, partner_code, app_name, type, timestamp, event, sequence_id) values(?, ?, ?, ?, ?, ?, ?)");
        PreparedStatement pstmtInsert3 = client.getSession().prepare("insert into velocity_global(attribute, partner_code, app_name, type, timestamp, event, sequence_id) values(?, ?, ?, ?, ?, ?, ?)");
        pstmtInsert1.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);
        pstmtInsert2.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);
        pstmtInsert3.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);

        long end2 = System.currentTimeMillis();
        for(Row row : rows){
            String attribute = row.getString("attribute");
            String partner_code = row.getString("partner_code");
            String app_name = row.getString("app_name");
            String type = row.getString("type");
            Long timestamp = row.getLong("timestamp");
            String event = row.getString("event");
            String sequence_id = row.getString("sequence_id");

            client.execute(pstmtInsert1, new Object[]{attribute, partner_code, app_name, type, timestamp, event, sequence_id});
            client.execute(pstmtInsert2, new Object[]{attribute, partner_code, app_name, type, timestamp, event, sequence_id});
            client.execute(pstmtInsert3, new Object[]{attribute, partner_code, app_name, type, timestamp, event, sequence_id});
        }

        System.out.println("1:" + (end - start));
        System.out.println("2:" + (end2 - start2));
    }

    //插入数据: Exception in thread "main" com.datastax.driver.core.exceptions.InvalidQueryException: You must use conditional updates for serializable writes
    //Exception in thread "main" com.datastax.driver.core.exceptions.InvalidTypeException: Invalid type for value 4 of CQL type bigint, expecting class java.lang.Long but class java.lang.Integer provided
    public static void insertByClient(){
        PreparedStatement pstmtInsert = client.getSession().prepare("insert into velocity_test(attribute, partner_code, app_name, type, timestamp, event, sequence_id) values(?, ?, ?, ?, ?, ?, ?)");
        pstmtInsert.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);
        for(int i=0;i<100;i++) {
            client.execute(pstmtInsert, new Object[]{"test2", "koudai", "koudai_ios", "account", Long.valueOf(12345678 + i), "raw event json str", "" + Long.valueOf(12345678 + i)});
        }
    }

    public static void insertUsingTTL(){
        PreparedStatement pstmtInsert = client.getSession().prepare(
                "insert into velocity(attribute, partner_code, app_name, type, timestamp, event, sequence_id) values(?, ?, ?, ?, ?, ?, ?) using ttl 3600");
        pstmtInsert.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);
        for(int i=0;i<8000;i++) {
            client.execute(pstmtInsert, new Object[]{
                    "test3", "koudai", "koudai_ios", "account", Long.valueOf(12345678 + i), "raw event json str", "" + Long.valueOf(12345678 + i)
            });
        }
    }

    public static void insertByAPI(){
        QueryOptions options = new QueryOptions();
        options.setConsistencyLevel(ConsistencyLevel.ONE);

        Cluster cluster = Cluster.builder()
                .addContactPoint("192.168.6.52")
                 //.withCredentials("cassandra", "cassandra")
                .withQueryOptions(options)
                .build();
        Session session = cluster.connect("forseti");

        insertVelocity(session, "velocity_app");
        insertVelocity(session, "velocity_partner");
        insertVelocity(session, "velocity_global");
        insertVelocity(session, "velocity_test");

        session.close();
        cluster.close();
    }
    public static void insertVelocity(Session session, String tbl){
        for(int i=0;i<8000;i++){
            RegularStatement insert = QueryBuilder.insertInto("forseti", tbl).values(
                    "attribute,partner_code,app_name,type,timestamp,event,sequence_id".split(","),
                    new Object[]{
                            "test", "koudai", "koudai_ios", "account", 12345678+i, "raw event json str", ""+12345678+i
                    });
            session.execute(insert);
        }
    }

    public static void velocityQuery(){
        PreparedStatement pstmtSelectAppScope = client.getSession().prepare(velocity);
        PreparedStatement preparedStatementOrder = client.getSession().prepare(velocityOrder);

        PreparedStatement preparedStatementOrder_app = client.getSession().prepare(velocity_app);
        PreparedStatement preparedStatementOrder_partner = client.getSession().prepare(velocity_partner);
        PreparedStatement preparedStatementOrder_global = client.getSession().prepare(velocity_global);

        pstmtSelectAppScope.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);
        preparedStatementOrder.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);

        preparedStatementOrder_app.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);
        preparedStatementOrder_partner.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);
        preparedStatementOrder_global.setConsistencyLevel(ConsistencyLevel.ONE).setRetryPolicy(FallthroughRetryPolicy.INSTANCE);

        query(preparedStatementOrder_app, new Object[]{"test", "koudai", "koudai_ios", "account"});
        query(preparedStatementOrder_partner, new Object[]{"test", "koudai"});
        query(preparedStatementOrder_global, new Object[]{"test"});
    }

    public static void query(PreparedStatement pstmt, Object... params){
        int loopTime = 1;
        //使用不同的row-key查询,主要是为了排除多次查询是否有Cache的影响.
        long start1 = System.currentTimeMillis();

        for(int i=0;i<loopTime;i++) {
            List<Row> rows = client.getAllOfSize(pstmt, 1000, params);
        }
        long end1 = System.currentTimeMillis();

        System.out.println("Cost(ms):" + (end1-start1));
    }
}
