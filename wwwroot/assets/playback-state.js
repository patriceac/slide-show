export const photoKey = (photo) => photo.cacheKey || photo.url || photo.name;

// Keep a shuffled sequence and the current photo when the catalog is refreshed.
export function arrangePhotos(photos, order = "shuffle", bookmark = null) {
  let result = [...photos];
  if (order === "name")
    result.sort((a, b) =>
      a.name.localeCompare(b.name, undefined, {
        numeric: true,
        sensitivity: "base",
      }),
    );
  else if (order === "date")
    result.sort(
      (a, b) =>
        (Number(a.modifiedAt) || 0) - (Number(b.modifiedAt) || 0) ||
        a.name.localeCompare(b.name),
    );
  else {
    const byKey = new Map(result.map((photo) => [photoKey(photo), photo]));
    const kept =
      bookmark?.order === order
        ? (bookmark.keys || [])
            .filter((key) => byKey.has(key))
            .map((key) => byKey.get(key))
        : [];
    const used = new Set(kept.map(photoKey));
    const added = result.filter((photo) => !used.has(photoKey(photo)));
    for (let i = added.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [added[i], added[j]] = [added[j], added[i]];
    }
    result = [...kept, ...added];
  }
  return {
    photos: result,
    index: Math.max(
      0,
      result.findIndex((photo) => photoKey(photo) === bookmark?.current),
    ),
  };
}
