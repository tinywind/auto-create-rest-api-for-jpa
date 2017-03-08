package org.tinywind.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.metadata.ClassMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author tinywind
 * @since 2017-03-04
 */
@RestController
@RequestMapping("!api")
public class AutoCreateRestApiController {
	private final Log log = LogFactory.getLog(getClass());
	private final SessionFactory sessionFactory;
	private Map<String, ClassMetadata> mapNameMeta = new HashMap<>();

	@Autowired
	public AutoCreateRestApiController(SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * object name to uri name
	 */
	private static String dashName(String originName) {
		StringBuilder buf = new StringBuilder(originName.replace('.', '-'));
		for (int i = 1; i < buf.length() - 1; i++) {
			if (Character.isUpperCase(buf.charAt(i))) {
				buf.insert(i++, '-');
			}
		}
		return buf.toString().toLowerCase(Locale.ROOT);
	}

	/**
	 * uri name to object name
	 */
	private static String originName(String dashName) {
		StringBuilder buf = new StringBuilder(dashName);
		buf.replace(0, 1, String.valueOf(buf.charAt(0)).toUpperCase());
		for (int i = 1; i < buf.length() - 1; i++) {
			if (buf.charAt(i) == '-') {
				buf.delete(i, i + 1);
				buf.replace(i, i + 1, String.valueOf(buf.charAt(i)).toUpperCase());
				i--;
			}
		}
		return buf.toString().toLowerCase(Locale.ROOT);
	}

	/**
	 * uri name to table name
	 */
	private static String tableName(String dashName) {
		final String originName = originName(dashName);
		return originName.substring(0, 1).toUpperCase() + originName.substring(1);
	}

	private static Method getSetter(Class<?> klass, String fieldName, Class<?> paramType) throws NoSuchMethodException {
		return klass.getMethod("set" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1), paramType);
	}

	private static Method getGetter(Class<?> klass, String fieldName) throws NoSuchMethodException {
		return klass.getMethod("get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1));
	}

	private static List<Field> getIdFieldList(Class<?> klass) {
		final List<Field> idFieldList = new ArrayList<>();

		for (Class<?> current = klass; !current.equals(Object.class); current = current.getSuperclass())
			idFieldList.addAll(Arrays.stream(current.getDeclaredFields())
					.filter(field -> field.isAnnotationPresent(Id.class))
					.collect(Collectors.toList()));

		return idFieldList;
	}

	@PostConstruct
	public void setup() throws IOException, ClassNotFoundException {
		sessionFactory.getAllClassMetadata().forEach((packageName, meta) -> {
			final int iClassName = packageName.lastIndexOf(".");
			final String className = packageName.substring(iClassName >= 0 ? iClassName + 1 : 0);
			mapNameMeta.put(dashName(className), meta);
		});
	}

	private Field getIdField(Class mappedClass) throws IllegalAccessException {
		final List<Field> idFieldList = getIdFieldList(mappedClass);

		if (idFieldList.size() != 1)
			throw new IllegalAccessException(mappedClass.getName() + "'s Id fields: " + idFieldList.stream().map(Field::getName).collect(Collectors.toList()));

		return idFieldList.get(0);
	}

	@RequestMapping(value = "{objectType}", method = RequestMethod.GET, params = ".search")
	public ResponseEntity<?> search(HttpServletRequest request, @PathVariable String objectType) throws IllegalAccessException {
		final Map<String, String[]> parameterMap = request.getParameterMap();
		final Map<String, List<Operation>> mapKeyOperations = new HashMap<>();

		// /!api/{objectType}?.search&{column}={operand}&{column}.{index of column}={operator}
		// /!api/parent?.search&name=p&name.0=LIKE
		final Predicate<String> isOperator = key -> {
			String[] split = key.split("[.]");
			return split.length >= 2 && !split[1].chars().filter(c -> !Character.isDigit(c)).findAny().isPresent();
		};

		parameterMap.keySet().stream().filter(isOperator.negate()).forEach(key -> {
			if (key.equalsIgnoreCase(".search")) return;
			for (String value : parameterMap.get(key))
				mapKeyOperations.computeIfAbsent(key, s -> new ArrayList<>()).add(new Operation(key, value));
		});

		try {
			parameterMap.keySet().stream().filter(isOperator).forEach(key -> {
				String[] split = key.split("[.]");
				List<Operation> operations = mapKeyOperations.get(split[0]);
				operations.get(Integer.valueOf(split[1])).operator = Operation.Operator.of(parameterMap.get(key)[0]);
			});
		} catch (NullPointerException e) {
			return new ResponseEntity<>("invalid parameters", HttpStatus.BAD_REQUEST);
		}

		final StringBuilder whereString = new StringBuilder();
		whereString.append(mapKeyOperations.values().size() == 0 ? "" : " WHERE ");
		mapKeyOperations.values().forEach(operations -> operations.forEach(whereString::append));

		try (final Session session = sessionFactory.openSession()) {
			List list = session.createQuery("FROM " + tableName(objectType) + whereString).list();
			return new ResponseEntity<>(list, HttpStatus.OK);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@SuppressWarnings("unchecked")
	@RequestMapping(value = "**", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> processApi(HttpServletRequest request, @RequestBody(required = false) String body) throws IllegalAccessException {
		final String method = request.getMethod().toUpperCase();
		final String[] uris = request.getRequestURI().split("[/]"); // : /!api/{}...

		if (uris.length < 3) return new ResponseEntity<>(HttpStatus.BAD_REQUEST);

		final ClassMetadata metadata = mapNameMeta.get(originName(uris[2]));
		if (metadata == null) return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		final Class mappedClass = metadata.getMappedClass();
		final Field idField = getIdField(mappedClass);

		try (final Session session = sessionFactory.openSession()) {
			switch (method) {
				case "GET": {
					// GET /!api/{objectType}
					if (uris.length < 4)
						return new ResponseEntity<>(session.createCriteria(mappedClass).list(), HttpStatus.OK);

					// GET /!api/{objectType}/{id}
					Method valueOf = idField.getType().getMethod("valueOf", String.class);
					Object idValue = valueOf.invoke(null, uris[3]);

					final Object target = session.get(mappedClass, (Serializable) idValue);
					return new ResponseEntity<>(target, HttpStatus.OK);
				}
				case "POST": { // POST /!api/{objectType}
					ObjectMapper mapper = new ObjectMapper();
					mapper.configure(DeserializationFeature.EAGER_DESERIALIZER_FETCH, true);
					mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true);
					mapper.configure(DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY, true);
					final Object value = mapper.readValue(body, mappedClass);
					final Serializable id = session.save(value);

					final List<Field> references = Arrays.stream(mappedClass.getDeclaredFields())
							.filter(field -> field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class))
							.collect(Collectors.toList());

					for (Field reference : references) {
						Object listMember = getGetter(mappedClass, reference.getName()).invoke(value);
						Collection<Object> list = listMember instanceof Object[] ? Arrays.asList((Object[]) listMember) : (Collection<Object>) listMember;

						if (list != null)
							for (Object o : list) {
								final Class<?> oClass = o.getClass();
								try {
									getSetter(oClass, getIdField(oClass).getName(), id.getClass()).invoke(o, id);
								} catch (IllegalAccessException e) {
									log.error(oClass.getName() + ": idFieldList.size() != 1");
									continue;
								} catch (Exception e) {
									log.error(e.getMessage(), e);
									continue;
								}
								session.save(o);
							}
					}

					session.flush();
					return new ResponseEntity<>(HttpStatus.OK);
				}
				case "PUT": { // PUT /!api/{objectType}/{id}
					if (uris.length < 4) return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
					Method valueOf = idField.getType().getMethod("valueOf", String.class);
					Object idValue = valueOf.invoke(null, uris[3]);

					final Object value = new ObjectMapper().readValue(body, mappedClass);
					getSetter(mappedClass, idField.getName(), idField.getType()).invoke(value, idValue);

					session.update(value);
					session.flush();
					return new ResponseEntity<>(HttpStatus.OK);
				}
				case "DELETE": { // DELETE /!api/{objectType}/{id}
					if (uris.length < 4) return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
					Method valueOf = idField.getType().getMethod("valueOf", String.class);
					Object idValue = valueOf.invoke(null, uris[3]);

					final Object target = session.get(mappedClass, (Serializable) idValue);

					session.delete(target);
					session.flush();
					return new ResponseEntity<>(HttpStatus.OK);
				}
				default: {
					return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	static class Operation {
		String column;
		Operator operator = Operator.EQUAL;
		String value;

		Operation(String column, String value) {
			this.column = column;
			this.value = value;
		}

		Operation(String column, Operator operator, String value) {
			this.column = column;
			this.operator = operator;
			this.value = value;
		}

		@Override
		public String toString() {
			return toString(false);
		}

		String toString(boolean stringType) {
			if (operator.equals(Operator.LIKE)) return " " + column + " LIKE '%" + value + "%' ";
			return " " + column + " " + operator + " " + (stringType ? value : "'" + value.replaceAll("[']", "\\'") + "'");
		}

		enum Operator {
			LIKE("LIKE"), EQUAL("="), GREATER(">"), LESS("<"), GREATER_OR_EQUAL(">="), LESS_OR_EQUAL("<=");

			String string;

			Operator(String string) {
				this.string = string;
			}

			static Operator of(String string) {
				for (Operator operator : Operator.values()) {
					if (operator.string.equals(string))
						return operator;
				}
				return null;
			}

			@Override
			public String toString() {
				return string;
			}
		}
	}
}
