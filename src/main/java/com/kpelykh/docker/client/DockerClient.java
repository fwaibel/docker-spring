package com.kpelykh.docker.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.kpelykh.docker.client.model.ChangeLog;
import com.kpelykh.docker.client.model.CommitConfig;
import com.kpelykh.docker.client.model.Container;
import com.kpelykh.docker.client.model.ContainerConfig;
import com.kpelykh.docker.client.model.ContainerCreateResponse;
import com.kpelykh.docker.client.model.ContainerInspectResponse;
import com.kpelykh.docker.client.model.ContainerTopResponse;
import com.kpelykh.docker.client.model.ContainerWaitResponse;
import com.kpelykh.docker.client.model.HostConfig;
import com.kpelykh.docker.client.model.Image;
import com.kpelykh.docker.client.model.ImageCreateResponse;
import com.kpelykh.docker.client.model.ImageInspectResponse;
import com.kpelykh.docker.client.model.Info;
import com.kpelykh.docker.client.model.SearchItem;
import com.kpelykh.docker.client.model.Version;
import com.kpelykh.docker.client.utils.CompressArchiveUtil;

/**
 * @author Konstantin Pelykh (kpelykh@gmail.com)
 * @author Florian Waibel (fwaibel@eclipsesource.com)
 */
public class DockerClient {

	private static final Logger LOGGER = LoggerFactory.getLogger(DockerClient.class);

	private String dockerDeamonUrl;

	private RestTemplate restTemplate;

	// info and version return ContentType text/plain which is ignored by the
	// MJHMC by default.
	private RestTemplate textRestTemplate;

	public DockerClient() {
		this("http://localhost:4243");
	}

	private class DockerDaemonResponseErrorHandler extends DefaultResponseErrorHandler {
		@Override
		public boolean hasError(ClientHttpResponse response) throws IOException {
			if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
				return true;
			}
			return super.hasError(response);
		}

		@Override
		public void handleError(ClientHttpResponse response) throws IOException {
			if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
				throw new NotFoundException("Image or container not found.");
			}
			if (response.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR) {
				throw new DockerException();
			}
			super.handleError(response);
		}
	}

	public DockerClient(String serverUrl) {
		dockerDeamonUrl = serverUrl;
		restTemplate = new RestTemplate();
		restTemplate.setErrorHandler(new DockerDaemonResponseErrorHandler());

		textRestTemplate = new RestTemplate();
		List<HttpMessageConverter<?>> messageConverters = textRestTemplate.getMessageConverters();
		messageConverters.clear();
		MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
		List<MediaType> supportedMediaTypes = new ArrayList<MediaType>(converter.getSupportedMediaTypes());
		supportedMediaTypes.add(new MediaType("text", "plain"));
		messageConverters.add(converter);
		converter.setSupportedMediaTypes(supportedMediaTypes);

		// Experimental support for unix sockets:
		// client = new UnixSocketClient(clientConfig);
		// client.addFilter(new JsonClientFilter());
		// client.addFilter(new LoggingFilter());
	}

	public void setDockerDeamonUrl(String dockerDeamonUrl) {
		LOGGER.info("Changing docker deamon URL to '{}'", dockerDeamonUrl);
		this.dockerDeamonUrl = dockerDeamonUrl;
	}

	/**
	 * * MISC API *
	 */

	public Info info() throws DockerException {
		return textRestTemplate.getForObject(dockerDeamonUrl + "/info", Info.class);
	}

	public Version version() throws DockerException {
		return textRestTemplate.getForObject(dockerDeamonUrl + "/version", Version.class);
	}

	public int ping() {
		ResponseEntity<Object> entity = textRestTemplate.getForEntity(dockerDeamonUrl + "_ping", null);
		return entity.getStatusCode().value();
	}

	/**
	 ** IMAGES API
	 **/

	public void pull(String repository) throws DockerException {
		this.pull(repository, null, null);
	}

	public void pull(String repository, String tag) throws DockerException {
		this.pull(repository, tag, null);
	}

	public void pull(String repository, String tag, String registry) throws DockerException {
		Preconditions.checkNotNull(repository, "Repository was not specified");

		if (StringUtils.countMatches(repository, ":") == 1) {
			String repositoryTag[] = StringUtils.split(repository);
			repository = repositoryTag[0];
			tag = repositoryTag[1];

		}

		Map<String, String> params = new HashMap<String, String>();
		params.put("tag", tag);
		params.put("fromImage", repository);
		params.put("registry", registry);

		restTemplate.exchange(dockerDeamonUrl + "/images/create?tag={tag}&fromImage={fromImage}&registry={registry}", HttpMethod.POST,
				null, String.class, params);
	}

	/**
	 * Create an image by importing the given stream of a tar file.
	 * 
	 * @param repository the repository to import to
	 * @param tag any tag for this image
	 * @param imageStream the InputStream of the tar file
	 * @return an {@link ImageCreateResponse} containing the id of the imported image
	 * @throws DockerException if the import fails for some reason.
	 */
	// TODO - migrate new API
	/*
	 * public ImageCreateResponse importImage(String repository, String tag, InputStream imageStream) throws DockerException {
	 * Preconditions.checkNotNull(repository, "Repository was not specified"); Preconditions.checkNotNull(imageStream,
	 * "imageStream was not provided");
	 * 
	 * MultivaluedMap<String,String> params = new MultivaluedMapImpl(); params.add("repo", repository); params.add("tag", tag);
	 * params.add("fromSrc","-");
	 * 
	 * WebResource webResource = client.resource(restEndpointUrl + "/images/create").queryParams(params);
	 * 
	 * try { LOGGER.trace("POST: {}", webResource); return webResource.accept(MediaType
	 * .APPLICATION_OCTET_STREAM_TYPE).post(ImageCreateResponse .class,imageStream);
	 * 
	 * } catch (UniformInterfaceException exception) { if (exception.getResponse().getStatus() == 500) { throw new
	 * DockerException("Server error.", exception); } else { throw new DockerException(exception); } } }
	 */

	public List<SearchItem> search(String search) throws DockerException {
		SearchItem[] response = restTemplate.getForObject(dockerDeamonUrl + "/images/search?term={search}", SearchItem[].class, search);
		return Arrays.asList(response);
	}

	public void removeImage(String imageId) throws DockerException {
		Preconditions.checkState(!StringUtils.isEmpty(imageId), "Image ID can't be empty");

		restTemplate.delete(dockerDeamonUrl + "/images/{imageId}", imageId);
	}

	public void removeImages(List<String> images) throws DockerException {
		Preconditions.checkNotNull(images, "List of images can't be null");

		for (String imageId : images) {
			removeImage(imageId);
		}
	}

	public String getVizImages() throws DockerException {
		return restTemplate.getForObject(dockerDeamonUrl + "/images/viz", String.class);
	}

	public List<Image> getImages() throws DockerException {
		return this.getImages(null, false);
	}

	public List<Image> getImages(boolean allContainers) throws DockerException {
		return this.getImages(null, allContainers);
	}

	public List<Image> getImages(String name) throws DockerException {
		return this.getImages(name, false);
	}

	public List<Image> getImages(String name, boolean allImages) throws DockerException {
		Map<String, String> params = new HashMap<String, String>();
		params.put("filter", name);
		params.put("all", allImages ? "1" : "0");

		Image[] response = restTemplate.getForObject(dockerDeamonUrl + "/images/json?filter={filter}&all={all}", Image[].class, params);
		return Arrays.asList(response);
	}

	public ImageInspectResponse inspectImage(String imageId) throws DockerException {
		return restTemplate.getForObject(dockerDeamonUrl + "/images/{imageId}/json", ImageInspectResponse.class, imageId);
	}

	/**
	 ** CONTAINERS API
	 **/

	public ContainerCreateResponse createContainer(ContainerConfig containerConfig) throws DockerException {
		return createContainer(containerConfig, null);
	}

	public ContainerCreateResponse createContainer(ContainerConfig containerConfig, String containerName) throws DockerException {
		final HttpHeaders requestHeaders = new HttpHeaders();
		requestHeaders.setContentType(MediaType.APPLICATION_JSON);
		requestHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		final HttpEntity<ContainerConfig> requestEntity = new HttpEntity<ContainerConfig>(containerConfig, requestHeaders);

		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try {
			new ObjectMapper().writeValue(outputStream, containerConfig);
			LOGGER.debug("Creating a container with the following configuration: {}.", new String(outputStream.toByteArray()));
		} catch (JsonGenerationException e1) {
			e1.printStackTrace();
		} catch (JsonMappingException e1) {
			e1.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		String containerParameter = "";
		if (containerName != null) {
			containerParameter = "?name=" + containerName;
		}
		String response = null;
		response = restTemplate.postForObject(dockerDeamonUrl + "/containers/create" + containerParameter, requestEntity, String.class);
		try {
			return new ObjectMapper().readValue(response, ContainerCreateResponse.class);
		} catch (JsonParseException e) {
			throw new IllegalStateException(e);
		} catch (JsonMappingException e) {
			throw new IllegalStateException(e);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public List<Container> listContainers(boolean listAll) {
		Container[] response = restTemplate.getForObject(dockerDeamonUrl + "/containers/json?all={all}", Container[].class, listAll);
		return Arrays.asList(response);
	}

	public List<Container> listContainers(boolean allContainers, boolean latest) {
		return this.listContainers(allContainers, latest, -1, false, null, null);
	}

	public List<Container> listContainers(boolean allContainers, boolean latest, int limit) {
		return this.listContainers(allContainers, latest, limit, false, null, null);
	}

	public List<Container> listContainers(boolean allContainers, boolean latest, int limit, boolean showSize) {
		return this.listContainers(allContainers, latest, limit, showSize, null, null);
	}

	public List<Container> listContainers(boolean allContainers, boolean latest, int limit, boolean showSize, String since) {
		return this.listContainers(allContainers, latest, limit, false, since, null);
	}

	public List<Container> listContainers(boolean allContainers, boolean latest, int limit, boolean showSize, String since, String before) {
		Map<String, String> params = new HashMap<String, String>();
		params.put("limit", latest ? "1" : String.valueOf(limit));
		params.put("all", allContainers ? "1" : "0");
		params.put("since", since);
		params.put("before", before);
		params.put("size", showSize ? "1" : "0");

		Container[] response = restTemplate.getForObject(dockerDeamonUrl + "/containers/json", Container[].class, params);
		return Arrays.asList(response);
	}

	public void startContainer(String containerId) throws DockerException {
		this.startContainer(containerId, null);
	}

	public void startContainer(String containerId, HostConfig hostConfig) throws DockerException {
		restTemplate.postForLocation(dockerDeamonUrl + "/containers/{containerId}/start", hostConfig, containerId);
	}

	public ContainerInspectResponse inspectContainer(String containerId) throws DockerException {
		return restTemplate.getForObject(dockerDeamonUrl + "/containers/{containerId}/json", ContainerInspectResponse.class, containerId);
	}

	public ContainerTopResponse top(String containerId) throws DockerException {
		return restTemplate.getForObject(dockerDeamonUrl + "/containers/{containerId}/top", ContainerTopResponse.class, containerId);
	}

	public void removeContainer(String container) throws DockerException {
		this.removeContainer(container, false);
	}

	public void removeContainer(String containerId, boolean removeVolumes) throws DockerException {
		Preconditions.checkState(!StringUtils.isEmpty(containerId), "Container ID can't be empty");

		restTemplate.delete(dockerDeamonUrl + "/containers/{containerId}?v={removeVolumes}", containerId, removeVolumes ? "1" : "0");
	}

	public void removeContainers(List<String> containers, boolean removeVolumes) throws DockerException {
		Preconditions.checkNotNull(containers, "List of containers can't be null");

		for (String containerId : containers) {
			removeContainer(containerId, removeVolumes);
		}
	}

	public ContainerWaitResponse waitContainer(String containerId) throws DockerException {
		return restTemplate.postForObject(dockerDeamonUrl + "/containers/{containerId}/wait", null, ContainerWaitResponse.class,
				containerId);
	}

	public InputStream logContainer(String containerId) throws DockerException {
		return logContainer(containerId, false);
	}

	public InputStream logContainerStream(String containerId) throws DockerException {
		return logContainer(containerId, true);
	}

	private InputStream logContainer(String containerId, boolean stream) throws DockerException {

		ResponseExtractor<InputStream> responseExtractor = new ResponseExtractor<InputStream>() {
			@Override
			public InputStream extractData(ClientHttpResponse response) throws IOException {
				String result = IOUtils.toString(response.getBody());
				return new ByteArrayInputStream(result.getBytes());
			}
		};

		Map<String, String> params = new HashMap<String, String>();
		params.put("containerId", containerId);
		params.put("logs", "1");
		params.put("stdout", "1");
		params.put("stderr", "1");
		params.put("stream", stream ? "1" : "0"); // this parameter keeps stream
													// open indefinitely

		return restTemplate.execute(dockerDeamonUrl
				+ "/containers/{containerId}/attach?logs={logs}&stdout={stdout}&stderr={stderr}&stream={stream}", HttpMethod.POST, null,
				responseExtractor, params);
	}

	public List<ChangeLog> containterDiff(String containerId) throws DockerException {
		ChangeLog[] response = restTemplate.getForObject(dockerDeamonUrl + "/containers/{containerId}/changes", ChangeLog[].class,
				containerId);
		return Arrays.asList(response);
	}

	public void stopContainer(String containerId) throws DockerException {
		this.stopContainer(containerId, 10); // wait 10 seconds before killing the container
	}

	public void stopContainer(String containerId, int timeout) throws DockerException {
		restTemplate.postForLocation(dockerDeamonUrl + "/containers/{containerId}/stop?t={timeout}", null, containerId, timeout);
	}

	public void restart(String containerId, int timeout) throws DockerException {
		restTemplate.postForLocation(dockerDeamonUrl + "/containers/{containerId}/restart?t={timeout}", null, containerId, timeout);
	}

	public void kill(String containerId) throws DockerException {
		restTemplate.postForLocation(dockerDeamonUrl + "/containers/{containerId}/kill", null, containerId);
	}

	private static class CommitResponse {

		@JsonProperty("Id")
		public String id;

		@Override
		public String toString() {
			return "CommitResponse{" + "id=" + id + '}';
		}

	}

	public String commit(CommitConfig commitConfig) throws DockerException {
		Preconditions.checkNotNull(commitConfig.getContainer(), "Container ID was not specified");

		Map<String, String> params = new HashMap<String, String>();
		params.put("container", commitConfig.getContainer());
		params.put("repo", commitConfig.getRepo());
		params.put("tag", commitConfig.getTag());
		params.put("m", commitConfig.getMessage());
		params.put("author", commitConfig.getAuthor());
		params.put("run", commitConfig.getRun());

		String response = restTemplate.postForObject(dockerDeamonUrl
				+ "/commit?container={container}&repo={repo}&tag={tag}&m={m}&author={author}&run={run}", null, String.class, params);

		try {
			return new ObjectMapper().readValue(response, CommitResponse.class).id;
		} catch (JsonParseException e) {
			throw new IllegalStateException(e);
		} catch (JsonMappingException e) {
			throw new IllegalStateException(e);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public InputStream build(File dockerFolder) throws DockerException {
		return this.build(dockerFolder, null);
	}

	public InputStream build(File dockerFolder, String tag) throws DockerException {
		return this.build(dockerFolder, tag, false);
	}

	private static boolean isFileResource(String resource) {
		URI uri;
		try {
			uri = new URI(resource);
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
		return uri.getScheme() == null || "file".equals(uri.getScheme());
	}

	public InputStream build(File dockerFolder, String tag, boolean noCache) throws DockerException {
		Preconditions.checkNotNull(dockerFolder, "Folder is null");
		Preconditions.checkArgument(dockerFolder.exists(), "Folder %s doesn't exist", dockerFolder);
		Preconditions.checkState(new File(dockerFolder, "Dockerfile").exists(), "Dockerfile doesn't exist in " + dockerFolder);

		// ARCHIVE TAR
		String archiveNameWithOutExtension = UUID.randomUUID().toString();

		File dockerFolderTar = null;

		try {
			File dockerFile = new File(dockerFolder, "Dockerfile");
			List<String> dockerFileContent = FileUtils.readLines(dockerFile);

			if (dockerFileContent.size() <= 0) {
				throw new DockerException(String.format("Dockerfile %s is empty", dockerFile));
			}

			List<File> filesToAdd = new ArrayList<File>();
			filesToAdd.add(dockerFile);

			for (String cmd : dockerFileContent) {
				if (StringUtils.startsWithIgnoreCase(cmd.trim(), "ADD")) {
					String addArgs[] = StringUtils.split(cmd, " \t");
					if (addArgs.length != 3) {
						throw new DockerException(String.format("Wrong format on line [%s]", cmd));
					}

					String resource = addArgs[1];

					if (isFileResource(resource)) {
						File src = new File(resource);
						if (!src.isAbsolute()) {
							src = new File(dockerFolder, resource).getCanonicalFile();
						} else {
							throw new DockerException(String.format("Source file %s must be relative to %s", src, dockerFolder));
						}

						if (!src.exists()) {
							throw new DockerException(String.format("Source file %s doesn't exist", src));
						}
						if (src.isDirectory()) {
							filesToAdd.addAll(FileUtils.listFiles(src, null, true));
						} else {
							filesToAdd.add(src);
						}
					}
				}
			}

			dockerFolderTar = CompressArchiveUtil.archiveTARFiles(dockerFolder, filesToAdd, archiveNameWithOutExtension);

		} catch (IOException ex) {
			FileUtils.deleteQuietly(dockerFolderTar);
			throw new DockerException("Error occurred while preparing Docker context folder.", ex);
		}

		final HttpHeaders requestHeaders = new HttpHeaders();
		requestHeaders.set("Content-Type", "application/tar");
		HttpEntity<byte[]> requestEntity;
		try {
			FileInputStream openInputStream = FileUtils.openInputStream(dockerFolderTar);
			byte[] byteArray = IOUtils.toByteArray(openInputStream);
			requestEntity = new HttpEntity<byte[]>(byteArray, requestHeaders);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}

		final ResponseEntity<String> response = restTemplate.exchange(dockerDeamonUrl + "/build?t={tag}", HttpMethod.POST, requestEntity,
				String.class, tag);

		return new ByteArrayInputStream(response.getBody().getBytes());
	}

	public RestTemplate getRestTemplate() {
		return restTemplate;
	}

}
