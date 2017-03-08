package org.tinywind.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import java.util.List;

/**
 * @author tinywind
 * @since 2017-03-04
 */
@Entity
public class Parent {
	@Id
	@GeneratedValue
	private Long id;

	private String name;

	@JsonIgnore
	@OneToMany(mappedBy="parent")
	private List<Child> children;

	public Parent() {
	}

	public Parent(String name) {
		this.name = name;
	}

	public long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<Child> getChildren() {
		return children;
	}

	public void setChildren(List<Child> children) {
		this.children = children;
	}
}
