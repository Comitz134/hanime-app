/*
 * MangaDex, as a SEKAI source.
 *
 * The contract is five functions, each returning a JSON object:
 *
 *   popular(page)          -> { items: [card], hasNext: true|false }
 *   search(query, page)    -> { items: [card], hasNext: true|false }
 *   title(id)              -> card
 *   chapters(id)           -> { chapters: [chapter] }
 *   pages(chapterId)       -> { pages: ["https://..."] }
 *
 * `httpGet(url[, headersJson])` is the only way out. There is no fetch, no
 * DOM and no Java: a source gets the network and nothing else.
 *
 * A card is what the app draws: id, title, poster, and any of type, year,
 * status, description, rating, latestChapter, genres, malId. A chapter is
 * id, number, name, at (shown) and created (epoch seconds, for the estimate).
 * Ids are opaque to the app; it prefixes them with this source's id itself.
 */

var API = "https://api.mangadex.org";
var COVERS = "https://uploads.mangadex.org/covers/";
var PAGE = 24;

function query(params) {
  var out = [];
  for (var i = 0; i < params.length; i++) {
    out.push(encodeURIComponent(params[i][0]) + "=" + encodeURIComponent(params[i][1]));
  }
  return out.join("&");
}

function api(path, params) {
  var url = API + path;
  if (params && params.length) url += "?" + query(params);
  return JSON.parse(httpGet(url));
}

function englishTitle(attributes) {
  var title = attributes.title || {};
  if (title.en) return title.en;
  var alt = attributes.altTitles || [];
  for (var i = 0; i < alt.length; i++) {
    if (alt[i].en) return alt[i].en;
  }
  var keys = Object.keys(title);
  return keys.length ? title[keys[0]] : "";
}

function coverOf(manga) {
  var relations = manga.relationships || [];
  for (var i = 0; i < relations.length; i++) {
    var relation = relations[i];
    if (relation.type !== "cover_art") continue;
    var name = relation.attributes && relation.attributes.fileName;
    if (name) return COVERS + manga.id + "/" + name + ".512.jpg";
  }
  return "";
}

function genresOf(attributes) {
  var out = [];
  var tags = attributes.tags || [];
  for (var i = 0; i < tags.length; i++) {
    var names = tags[i].attributes && tags[i].attributes.name;
    if (names && names.en) out.push(names.en);
  }
  return out;
}

function plain(text) {
  return String(text || "").replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
}

function statusOf(status) {
  if (status === "ongoing") return "Releasing";
  if (status === "completed") return "Finished";
  if (status === "hiatus") return "On Hiatus";
  if (status === "cancelled") return "Cancelled";
  return status || "";
}

function card(manga) {
  var a = manga.attributes;
  var description = a.description || {};
  return {
    id: manga.id,
    title: englishTitle(a),
    poster: coverOf(manga),
    type: "Manga",
    year: a.year ? String(a.year) : "",
    status: statusOf(a.status),
    description: plain(description.en || description["pt-br"] || ""),
    genres: genresOf(a),
    rating: "",
    latestChapter: "",
    malId: ""
  };
}

function list(path, params, page, ordered) {
  var all = params.slice(0);
  all.push(["limit", String(PAGE)]);
  all.push(["offset", String((page - 1) * PAGE)]);
  all.push(["includes[]", "cover_art"]);
  all.push(["contentRating[]", "safe"]);
  all.push(["contentRating[]", "suggestive"]);
  all.push(["hasAvailableChapters", "true"]);
  if (ordered) all.push(["order[" + ordered + "]", "desc"]);

  var body = api(path, all);
  var data = body.data || [];
  var items = [];
  for (var i = 0; i < data.length; i++) items.push(card(data[i]));
  var offset = body.offset || 0;
  var total = body.total || 0;
  return { items: items, hasNext: offset + data.length < total };
}

function popular(page) {
  return list("/manga", [], page || 1, "followedCount");
}

function search(keyword, page) {
  if (!keyword) return popular(page);
  var params = [];
  params.push(["title", keyword]);
  return list("/manga", params, page || 1, "relevance");
}

function title(id) {
  var body = api("/manga/" + encodeURIComponent(id), [["includes[]", "cover_art"]]);
  if (!body.data) throw new Error("MangaDex did not find that title.");
  return card(body.data);
}

function chapters(id) {
  var out = [];
  var offset = 0;
  for (var round = 0; round < 3; round++) {
    var body = api("/manga/" + encodeURIComponent(id) + "/feed", [
      ["translatedLanguage[]", "en"],
      ["order[chapter]", "desc"],
      ["limit", "500"],
      ["offset", String(offset)]
    ]);
    var data = body.data || [];
    for (var i = 0; i < data.length; i++) {
      var chapter = data[i];
      var a = chapter.attributes;
      // A licensed chapter is an externalUrl with no pages of its own; it can
      // be linked but not read here, so it is not a row this reader can open.
      if (a.externalUrl) continue;
      if (!a.pages) continue;
      var published = a.publishAt || "";
      out.push({
        id: chapter.id,
        number: a.chapter || "",
        name: a.title || "",
        at: published ? published.substring(0, 10) : "",
        created: published ? Math.floor(Date.parse(published) / 1000) : 0
      });
    }
    offset += data.length;
    if (data.length < 500) break;
  }
  return { chapters: out };
}

function pages(chapterId) {
  var body = api("/at-home/server/" + encodeURIComponent(chapterId), []);
  if (!body.chapter || !body.chapter.dataSaver) {
    throw new Error("MangaDex has no readable pages for that chapter (it may be an external chapter).");
  }
  var base = body.baseUrl;
  var hash = body.chapter.hash;
  var files = body.chapter.dataSaver;
  var out = [];
  for (var i = 0; i < files.length; i++) {
    out.push(base + "/data-saver/" + hash + "/" + files[i]);
  }
  return { pages: out };
}
