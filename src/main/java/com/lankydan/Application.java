package com.lankydan;

import com.lankydan.cassandra.person.Person;
import com.lankydan.cassandra.person.PersonKey;
import com.lankydan.cassandra.person.repository.PersonRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class Application implements CommandLineRunner {

  private final PersonRepository personRepository;

  @Autowired
  public Application(PersonRepository personRepository) {
    this.personRepository = personRepository;
  }

  public static void main(final String args[]) {
    SpringApplication.run(Application.class);
  }

  @Override
  public void run(String... args) {
    final Person john =
        new Person(new PersonKey("John", LocalDateTime.now(), UUID.randomUUID()), "A", 1000);

    personRepository.insert(john);
    personRepository.findByKeyFirstName("John").forEach(System.out::println);
  }
}
