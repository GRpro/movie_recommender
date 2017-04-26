package gr.ml.analytics.service

import gr.ml.analytics.domain.Item

import scala.concurrent.Future

trait ItemService {

  /**
    * Get item by id
    * @param schemaId the id of schema which the item has
    * @param itemId item id
    * @return item entity
    */
  def get(schemaId: Int, itemId: Int): Future[Option[Item]]

  /**
    * Get multiple items by their ids
    * @param schemaId the id of schema which the item has
    * @param itemIds list of ids
    * @return list of items
    */
  def get(schemaId: Int, itemIds: List[Int]): Future[List[Option[Item]]]

  /**
    * Stores item
    * @param schemaId the id of schema which the item has
    * @param item the item to be stored
    * @return the id of newly created item
    */
  def save(schemaId: Int, item: Item): Future[Option[Int]]
}

