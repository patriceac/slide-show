import { test } from 'node:test';
import assert from 'node:assert/strict';
import { arrangePhotos } from '../wwwroot/assets/playback-state.js';
const photos = [{cacheKey:'a',name:'Photo 10',modifiedAt:20},{cacheKey:'b',name:'Photo 2',modifiedAt:10}];
test('refresh and new photos preserve the shuffled sequence and current photo', () => {
  const result=arrangePhotos([...photos,{cacheKey:'c',name:'New'}],'shuffle',{order:'shuffle',keys:['b','a'],current:'a'});
  assert.deepEqual(result.photos.map(photo=>photo.cacheKey),['b','a','c']);
  assert.equal(result.index,1);
});
test('name and date order are predictable and resume the selected photo', () => {
  for(const order of ['name','date']) {
    const result=arrangePhotos(photos,order,{current:'a'});
    assert.deepEqual(result.photos.map(photo=>photo.cacheKey),['b','a']);
    assert.equal(result.index,1);
  }
  assert.equal(arrangePhotos([], 'name', {current:'a'}).index,0);
});
