package com.lankydan.cassandra.person.repository;

import com.lankydan.cassandra.person.Person;
import com.lankydan.cassandra.person.PersonKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PersonRepository extends CassandraRepository<Person, PersonKey> {

  List<Person> findByKeyFirstName(final String firstName);

  Person findOneByKeyFirstName(final String firstName);
}
