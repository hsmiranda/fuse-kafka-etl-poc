package com.redhat.consulting.poc.route;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import com.redhat.consulting.poc.processor.ExtractorProcessor;

@Component
public class RouteDefinitions extends RouteBuilder {

	@Override
	public void configure() throws Exception {		
		
		from("timer://foo?fixedRate=true&period=1000")
		  .to("jpa://com.redhat.consulting.poc.entity.PessoaEntity?namedQuery=findAll")
		  .split().body().streaming()
		  .process(new ExtractorProcessor())
		  .marshal().json(JsonLibrary.Jackson)
		  .to("kafka:person_topic?brokers=my-cluster-kafka-bootstrap.cicd-tools.svc:9092");
		
	}
	
}